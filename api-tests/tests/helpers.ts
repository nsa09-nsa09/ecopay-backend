import { APIRequestContext, expect } from "@playwright/test";
import crypto from "node:crypto";

// Unique-but-valid test data. DB persists across runs, so every user/phone must be unique.
let seq = 0;
export function unique() {
  seq += 1;
  const base = (Date.now() % 1_000_000_000) * 100 + seq; // fits well within 10 digits headroom
  const phoneDigits = String(base).slice(-9).padStart(9, "0");
  return {
    email: `e2e_${base}@test.kz`,
    phone: `+77${phoneDigits}`, // ^\+7\d{10}$  → +7 then 10 digits (7 + 9)
    password: "Test1234",
  };
}

export interface AuthedUser {
  token: string;
  refreshToken: string;
  id: number;
  email: string;
  phone: string;
}

const json = { "Content-Type": "application/json" };

/** Dev master phone-verification code (see application-dev.properties). */
export const DEV_PHONE_CODE = "000000";

/** Register a user. By default also verifies the phone (dev bypass) so the user can
 *  create/join/pay. Pass {verify:false} to keep the phone unverified. */
export async function register(
  api: APIRequestContext,
  displayName = "E2E User",
  opts: { verify?: boolean } = {},
): Promise<AuthedUser> {
  const u = unique();
  const res = await api.post(`auth/register`, {
    data: { email: u.email, password: u.password, displayName, phone: u.phone },
    headers: json,
  });
  expect(res.status(), `register ${u.email}`).toBe(201);
  const body = await res.json();

  // Registration is email-verification gated and issues NO tokens. In dev the email is
  // auto-verified (app.dev.auto-verify-email), so log in to obtain tokens for the test.
  const loginRes = await api.post(`auth/login`, {
    data: { email: u.email, password: u.password },
    headers: json,
  });
  expect(loginRes.status(), `login ${u.email}`).toBe(200);
  const loginBody = await loginRes.json();

  const user: AuthedUser = {
    token: loginBody.accessToken,
    refreshToken: loginBody.refreshToken,
    id: body.user.id,
    email: u.email,
    phone: u.phone,
  };

  if (opts.verify !== false) {
    const v = await api.post(`auth/phone/verify`, {
      ...auth(user.token),
      data: { phone: u.phone, code: DEV_PHONE_CODE },
    });
    expect(v.status(), "verify phone (dev)").toBe(204);
  }
  return user;
}

export function auth(token: string) {
  return { headers: { ...json, Authorization: `Bearer ${token}` } };
}

/** Create a DIGITAL room owned by `owner`. Returns the room response. */
export async function createDigitalRoom(
  api: APIRequestContext,
  ownerToken: string,
  overrides: Record<string, unknown> = {},
) {
  const res = await api.post(`rooms`, {
    ...auth(ownerToken),
    data: {
      serviceId: 2, // seeded Netflix (DIGITAL) — see V7
      tariffPlanId: 2,
      categoryId: 1,
      roomType: "DIGITAL",
      title: "Netflix Premium E2E",
      maxMembers: 4,
      priceTotal: 7290,
      pricePerMember: 1822.5,
      currency: "KZT",
      periodType: "MONTHLY",
      startDate: "2027-01-01T00:00:00",
      ...overrides,
    },
  });
  expect(res.status(), "create room").toBe(201);
  return res.json();
}

/** Create a TELECOM room owned by `owner`. Picks a real OPERATOR service + tariff from the catalog. */
export async function createTelecomRoom(
  api: APIRequestContext,
  ownerToken: string,
  overrides: Record<string, unknown> = {},
) {
  const svcRes = await api.get(`catalog/services`);
  const services = await svcRes.json();
  const operator = services.find((s: any) => s.providerType === "OPERATOR");
  expect(operator, "a seeded OPERATOR service exists").toBeTruthy();
  const tariffsRes = await api.get(`catalog/services/${operator.id}/tariffs`);
  const tariffs = await tariffsRes.json();
  const tariff = tariffs[0];

  const res = await api.post(`rooms`, {
    ...auth(ownerToken),
    data: {
      serviceId: operator.id,
      tariffPlanId: tariff?.id,
      categoryId: operator.categoryId,
      roomType: "TELECOM",
      title: `${operator.name} Family E2E`,
      maxMembers: tariff?.maxMembers ?? 4,
      priceTotal: tariff?.basePriceTotal ?? 6000,
      pricePerMember: Math.round(((tariff?.basePriceTotal ?? 6000) / (tariff?.maxMembers ?? 4)) * 100) / 100,
      currency: "KZT",
      periodType: "MONTHLY",
      startDate: "2027-01-01T00:00:00",
      providerName: operator.name,
      connectionType: tariff?.connectionType ?? "SIM",
      operatorTermsConfirmed: true,
      ...overrides,
    },
  });
  expect(res.status(), "create telecom room").toBe(201);
  return res.json();
}

/** Join a TELECOM room with a phone identifier. Returns membership. */
export async function joinTelecomRoom(
  api: APIRequestContext,
  memberToken: string,
  roomId: number,
  identifierValue = "+77011234567",
) {
  const res = await api.post(`rooms/${roomId}/members`, {
    ...auth(memberToken),
    data: { consentAccepted: true, identifierType: "PHONE", identifierValue },
  });
  expect(res.status(), "join telecom room").toBe(201);
  return res.json();
}

/** Join a room as `member` (DIGITAL → consent only). Returns membership. */
export async function joinRoom(api: APIRequestContext, memberToken: string, roomId: number) {
  const res = await api.post(`rooms/${roomId}/members`, {
    ...auth(memberToken),
    data: { consentAccepted: true },
  });
  expect(res.status(), "join room").toBe(201);
  return res.json();
}

/** Freedom Pay sandbox secret (backend .env FREEDOMPAY_SECRET_KEY). */
export const FREEDOMPAY_SECRET = process.env.FREEDOMPAY_SECRET_KEY ?? "vA6xhdLDfq3SVHf9";

/** MD5 signature the way Freedom Pay computes it: "<script>;<values sorted by key>;<secret>". */
export function fpSign(script: string, params: Record<string, string>, secret = FREEDOMPAY_SECRET) {
  const values = Object.keys(params)
    .filter((k) => k !== "pg_sig")
    .sort()
    .map((k) => params[k]);
  return crypto
    .createHash("md5")
    .update([script, ...values, secret].join(";"))
    .digest("hex");
}

/** Posts a signed Freedom Pay result callback to the backend webhook endpoint. */
export function postFreedomWebhook(api: APIRequestContext, params: Record<string, string>) {
  return api.post(`webhooks/freedompay/result`, {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    data: new URLSearchParams(params).toString(),
  });
}

/** Builds a signed success/failure result-callback payload for an intent. */
export function freedomWebhookParams(intent: any, result: "1" | "0", salt?: string) {
  const params: Record<string, string> = {
    pg_order_id: String(intent.id),
    pg_payment_id: String(intent.externalPaymentId),
    pg_amount: Number(intent.amount).toFixed(2),
    pg_currency: "KZT",
    pg_result: result,
    pg_can_reject: "1",
    pg_salt: salt ?? crypto.randomBytes(8).toString("hex"),
    pg_testing_mode: "1",
  };
  params.pg_sig = fpSign("result", params);
  return params;
}

/** Pay for a membership. With the mock gateway the charge succeeds synchronously;
 *  with the freedompay sandbox the intent comes back PENDING (hosted page redirect),
 *  so we finalize it by delivering the signed result webhook the gateway would send
 *  after the user completes the card form. Returns the terminal intent. */
export async function pay(api: APIRequestContext, memberToken: string, membershipId: number) {
  const res = await api.post(`payments/members/${membershipId}/intent`, {
    ...auth(memberToken),
    data: { idempotencyKey: `e2e-${membershipId}-${Date.now()}` },
  });
  expect(res.status(), "create intent").toBe(201);
  let intent = await res.json();

  if (intent.status === "PENDING" && intent.providerName === "freedompay") {
    const hook = await postFreedomWebhook(api, freedomWebhookParams(intent, "1"));
    expect(await hook.text(), "webhook accepted").toContain("<pg_status>ok</pg_status>");
    const refreshed = await api.get(`payments/intents/${intent.id}`, auth(memberToken));
    expect(refreshed.status(), "re-read intent").toBe(200);
    intent = await refreshed.json();
  }
  return intent;
}

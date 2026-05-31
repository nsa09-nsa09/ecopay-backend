import { APIRequestContext, expect } from "@playwright/test";

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
  const user: AuthedUser = {
    token: body.accessToken,
    refreshToken: body.refreshToken,
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

/** Pay for a membership via the mock gateway (dev). Returns the intent. */
export async function pay(api: APIRequestContext, memberToken: string, membershipId: number) {
  const res = await api.post(`payments/members/${membershipId}/intent`, {
    ...auth(memberToken),
    data: { idempotencyKey: `e2e-${membershipId}-${Date.now()}` },
  });
  expect(res.status(), "create intent").toBe(201);
  return res.json();
}

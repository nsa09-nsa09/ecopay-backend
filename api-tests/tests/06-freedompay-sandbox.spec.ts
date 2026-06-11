import { test, expect } from "@playwright/test";
import crypto from "node:crypto";
import {
  register,
  auth,
  createDigitalRoom,
  joinRoom,
  fpSign,
  freedomWebhookParams,
  postFreedomWebhook,
} from "./helpers";

// Freedom Pay sandbox sequence. Requires the backend to run with
// PAYMENTS_PROVIDER=freedompay and sandbox credentials (see backend .env).
// Covers: intent init against the live sandbox (real init_payment.php),
// redirect-back reconciliation (real get_status.php), and the async result
// webhook (signed exactly like Freedom Pay signs it: MD5 over
// "<script>;<sorted values>;<secret>" where script = last path segment of
// the result URL).

const webhookParams = (intent: any, salt: string, result: "1" | "0") =>
  freedomWebhookParams(intent, result, salt);
const postWebhook = postFreedomWebhook;

async function payIntent(request: any, memberToken: string, membershipId: number) {
  const res = await request.post(`payments/members/${membershipId}/intent`, {
    ...auth(memberToken),
    data: { idempotencyKey: `fp-e2e-${membershipId}-${Date.now()}` },
  });
  expect(res.status(), "create intent").toBe(201);
  return res.json();
}

test.describe("Freedom Pay sandbox sequence", () => {
  test("init -> reconcile (pending) -> webhook success -> membership paid", async ({ request }) => {
    const owner = await register(request, "FP Owner");
    const member = await register(request, "FP Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    // 1. Intent init hits the real sandbox init_payment.php.
    const intent = await payIntent(request, member.token, membership.id);
    test.skip(intent.providerName !== "freedompay", "backend not running with freedompay provider");
    expect(intent.status).toBe("PENDING");
    expect(intent.externalPaymentId, "sandbox returned pg_payment_id").toBeTruthy();
    expect(intent.paymentUrl, "sandbox returned hosted page URL").toContain("freedompay");
    expect(intent.requiresRedirect).toBe(true);

    // 2. Redirect-back reconciliation queries the real get_status.php. The card
    // form was never filled, so the sandbox reports "partial" -> stays PENDING.
    const confirm = await request.post(`payments/intents/${intent.id}/confirm-success`, {
      ...auth(member.token),
      data: {},
    });
    expect(confirm.status()).toBe(200);
    expect((await confirm.json()).status).toBe("PENDING");

    // 3. Async result webhook, signed the way Freedom Pay signs it.
    const salt = crypto.randomBytes(8).toString("hex");
    const hook = await postWebhook(request, webhookParams(intent, salt, "1"));
    expect(hook.status()).toBe(200);
    const hookBody = await hook.text();
    expect(hookBody).toContain("<pg_status>ok</pg_status>");
    expect(hookBody, "merchant reply must be signed").toContain("<pg_sig>");

    // 4. Intent finalized, membership advanced APPLIED -> PENDING (paid).
    const after = await request.get(`payments/intents/${intent.id}`, auth(member.token));
    expect((await after.json()).status).toBe("SUCCESS");
    const me = await request.get(`rooms/${room.id}/members/me`, auth(member.token));
    expect((await me.json()).status).toBe("PENDING");

    // 5. Duplicate webhook (same salt -> same provider request id) is deduped.
    const dup = await postWebhook(request, webhookParams(intent, salt, "1"));
    expect(await dup.text()).toContain("<pg_status>ok</pg_status>");
    const still = await request.get(`payments/intents/${intent.id}`, auth(member.token));
    expect((await still.json()).status).toBe("SUCCESS");
  });

  test("webhook with tampered signature is rejected and intent stays PENDING", async ({ request }) => {
    const owner = await register(request, "FP Sec Owner");
    const member = await register(request, "FP Sec Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    const intent = await payIntent(request, member.token, membership.id);
    test.skip(intent.providerName !== "freedompay", "backend not running with freedompay provider");
    expect(intent.status).toBe("PENDING");

    const params = webhookParams(intent, crypto.randomBytes(8).toString("hex"), "1");
    params.pg_sig = "0".repeat(32); // tampered
    const hook = await postWebhook(request, params);
    expect(hook.status()).toBe(200);
    expect(await hook.text()).toContain("<pg_status>error</pg_status>");

    const after = await request.get(`payments/intents/${intent.id}`, auth(member.token));
    expect((await after.json()).status).toBe("PENDING");
  });

  test("failed-payment webhook marks the intent FAILED", async ({ request }) => {
    const owner = await register(request, "FP Fail Owner");
    const member = await register(request, "FP Fail Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    const intent = await payIntent(request, member.token, membership.id);
    test.skip(intent.providerName !== "freedompay", "backend not running with freedompay provider");

    const params: Record<string, string> = {
      ...webhookParams(intent, crypto.randomBytes(8).toString("hex"), "0"),
    };
    delete params.pg_sig;
    params.pg_error_code = "100";
    params.pg_error_description = "Card declined (test)";
    params.pg_sig = fpSign("result", params);

    const hook = await postWebhook(request, params);
    expect(await hook.text()).toContain("<pg_status>ok</pg_status>");

    const after = await request.get(`payments/intents/${intent.id}`, auth(member.token));
    const body = await after.json();
    expect(body.status).toBe("FAILED");
    expect(body.failureCode).toBe("100");
  });
});

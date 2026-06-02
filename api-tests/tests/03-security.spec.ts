import { test, expect } from "@playwright/test";
import { register, auth, createDigitalRoom, joinRoom } from "./helpers";

// IDOR / authorization rules in the service layer.
test.describe("Security & IDOR", () => {
  test("member list requires authentication (no PII leak)", async ({ request }) => {
    const owner = await register(request, "Sec Owner");
    const room = await createDigitalRoom(request, owner.token);

    const noAuth = await request.get(`rooms/${room.id}/members`);
    expect([401, 403]).toContain(noAuth.status());

    const noAuthMe = await request.get(`rooms/${room.id}/members/me`);
    expect([401, 403]).toContain(noAuthMe.status());
  });

  test("a bystander cannot create a payment intent for someone else's membership", async ({ request }) => {
    const owner = await register(request, "Owner");
    const member = await register(request, "Member");
    const eve = await register(request, "Eve");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    const res = await request.post(`payments/members/${membership.id}/intent`, {
      ...auth(eve.token),
      data: { idempotencyKey: `evil-${Date.now()}` },
    });
    expect(res.status()).toBe(403);
  });

  test("a non-owner cannot grant access on a room they don't own", async ({ request }) => {
    const owner = await register(request, "Owner");
    const member = await register(request, "Member");
    const eve = await register(request, "Eve");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    const res = await request.patch(`rooms/${room.id}/members/${membership.id}/owner-access`, {
      ...auth(eve.token),
      data: { accessMethod: "invite_link" },
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });

  test("payment amount is server-side; client cannot influence it", async ({ request }) => {
    const owner = await register(request, "Owner");
    const member = await register(request, "Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    // Even if a client sends extra fields, the charged amount is room.pricePerMember.
    const res = await request.post(`payments/members/${membership.id}/intent`, {
      ...auth(member.token),
      data: { idempotencyKey: `amt-${Date.now()}`, amount: 1, currency: "USD" },
    });
    expect(res.status()).toBe(201);
    const intent = await res.json();
    expect(Number(intent.amount)).toBe(Number(room.pricePerMember));
    expect(intent.currency).toBe("KZT");
  });

  test("unverified phone cannot create a room (403)", async ({ request }) => {
    const owner = await register(request, "Unverified Owner", { verify: false });
    const res = await request.post(`rooms`, {
      ...auth(owner.token),
      data: {
        serviceId: 2, tariffPlanId: 2, categoryId: 1, roomType: "DIGITAL",
        title: "Should be blocked", maxMembers: 4, priceTotal: 7290, pricePerMember: 1822.5,
        currency: "KZT", periodType: "MONTHLY", startDate: "2027-01-01T00:00:00",
      },
    });
    expect(res.status()).toBe(403);
  });

  test("unverified phone cannot join a room (403)", async ({ request }) => {
    const owner = await register(request, "Verified Owner"); // verified
    const room = await createDigitalRoom(request, owner.token);
    const member = await register(request, "Unverified Member", { verify: false });
    const res = await request.post(`rooms/${room.id}/members`, {
      ...auth(member.token),
      data: { consentAccepted: true },
    });
    expect(res.status()).toBe(403);
  });

  test("cannot create a payment intent without a token", async ({ request }) => {
    const owner = await register(request, "Owner");
    const member = await register(request, "Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    const res = await request.post(`payments/members/${membership.id}/intent`, {
      data: { idempotencyKey: `noauth-${Date.now()}` },
      headers: { "Content-Type": "application/json" },
    });
    expect([401, 403]).toContain(res.status());
  });
});

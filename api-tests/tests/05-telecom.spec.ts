import { test, expect } from "@playwright/test";
import { register, auth, createTelecomRoom, joinTelecomRoom, pay } from "./helpers";

// TELECOM rooms: the member submits an encrypted identifier (phone/account); the owner
// can reveal it ONLY after a successful payment, and only the owner can.
test.describe("TELECOM rooms & identifier reveal", () => {
  const PHONE = "+77019876543";

  test("telecom lifecycle: join with identifier → pay → reveal (owner) → confirm → ACTIVE", async ({ request }) => {
    const owner = await register(request, "Tel Owner");
    const member = await register(request, "Tel Member");
    const room = await createTelecomRoom(request, owner.token);
    expect(room.roomType).toBe("TELECOM");

    const membership = await joinTelecomRoom(request, member.token, room.id, PHONE);
    expect(membership.status).toBe("APPLIED");

    // Before payment the member sees only a masked identifier, never the full value.
    const meBefore = await request.get(`rooms/${room.id}/members/me`, auth(member.token));
    const meBeforeBody = await meBefore.json();
    expect(meBeforeBody.identifierMasked).toBeTruthy();
    expect(meBeforeBody.identifierMasked).not.toBe(PHONE);

    // Owner cannot reveal before payment.
    const earlyReveal = await request.post(`rooms/${room.id}/members/${membership.id}/reveal-identifier`, {
      ...auth(owner.token),
      data: { reason: "setup" },
    });
    expect(earlyReveal.status()).toBeGreaterThanOrEqual(400);
    expect(earlyReveal.status()).toBeLessThan(500);

    // Pay (mock) → membership PENDING.
    await pay(request, member.token, membership.id);

    // Now the owner can reveal the full identifier, and it matches what the member entered.
    const reveal = await request.post(`rooms/${room.id}/members/${membership.id}/reveal-identifier`, {
      ...auth(owner.token),
      data: { reason: "Activating SIM for member" },
    });
    expect(reveal.status()).toBe(200);
    const revealed = await reveal.json();
    expect(revealed.identifierValue).toBe(PHONE);

    // Owner grants access, member confirms → ACTIVE.
    await request.patch(`rooms/${room.id}/members/${membership.id}/owner-access`, {
      ...auth(owner.token),
      data: { accessMethod: "family_plan_add" },
    });
    const confirm = await request.post(`rooms/${room.id}/members/me/confirm-access`, auth(member.token));
    expect(confirm.status()).toBe(200);
    expect((await confirm.json()).status).toBe("ACTIVE");
  });

  test("a non-owner cannot reveal a member's identifier", async ({ request }) => {
    const owner = await register(request, "Tel Owner");
    const member = await register(request, "Tel Member");
    const eve = await register(request, "Eve");
    const room = await createTelecomRoom(request, owner.token);
    const membership = await joinTelecomRoom(request, member.token, room.id, PHONE);
    await pay(request, member.token, membership.id);

    const res = await request.post(`rooms/${room.id}/members/${membership.id}/reveal-identifier`, {
      ...auth(eve.token),
      data: { reason: "curious" },
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });

  test("joining a TELECOM room without an identifier is rejected", async ({ request }) => {
    const owner = await register(request, "Tel Owner");
    const member = await register(request, "Tel Member");
    const room = await createTelecomRoom(request, owner.token);

    const res = await request.post(`rooms/${room.id}/members`, {
      ...auth(member.token),
      data: { consentAccepted: true }, // missing identifierType/Value
    });
    expect(res.status()).toBe(400);
  });
});

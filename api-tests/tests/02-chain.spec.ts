import { test, expect } from "@playwright/test";
import { register, auth, createDigitalRoom, joinRoom, pay } from "./helpers";

// The core business chain the project is about:
// register → create room → join → pay (mock) → owner grants → member confirms → ACTIVE
test.describe("Business chain (happy path)", () => {
  test("full DIGITAL room lifecycle to ACTIVE membership", async ({ request }) => {
    const owner = await register(request, "Chain Owner");
    const member = await register(request, "Chain Member");

    // 1. Create room
    const room = await createDigitalRoom(request, owner.token);
    expect(room.status).toBe("OPEN");
    expect(room.ownerUserId).toBe(owner.id);
    expect(Number(room.pricePerMember)).toBe(1822.5);

    // Room is publicly visible in the catalog list
    const list = await request.get(`rooms?size=50`);
    expect(list.status()).toBe(200);
    const listed = (await list.json()).items.some((r: any) => r.id === room.id);
    expect(listed).toBe(true);

    // 2. Join
    const membership = await joinRoom(request, member.token, room.id);
    expect(membership.status).toBe("APPLIED");
    expect(membership.userId).toBe(member.id);

    // 3. Pay (mock gateway → synchronous SUCCESS)
    const intent = await pay(request, member.token, membership.id);
    expect(intent.status).toBe("SUCCESS");
    expect(intent.providerName).toBe("mock");
    // Charged exactly the member share (no fee added on top)
    expect(Number(intent.amount)).toBe(Number(room.pricePerMember));

    // membership advanced APPLIED → PENDING
    let me = await request.get(`rooms/${room.id}/members/me`, auth(member.token));
    expect(me.status()).toBe(200);
    expect((await me.json()).status).toBe("PENDING");

    // 4. Owner grants access
    const memberId = membership.id;
    const grant = await request.patch(`rooms/${room.id}/members/${memberId}/owner-access`, {
      ...auth(owner.token),
      data: { accessMethod: "invite_link" },
    });
    expect(grant.status()).toBe(200);
    expect((await grant.json()).ownerAccessConfirmedAt).toBeTruthy();

    // 5. Member confirms access → ACTIVE
    const confirm = await request.post(`rooms/${room.id}/members/me/confirm-access`, auth(member.token));
    expect(confirm.status()).toBe(200);
    const confirmed = await confirm.json();
    expect(confirmed.status).toBe("ACTIVE");
    expect(confirmed.activatedAt).toBeTruthy();
  });

  test("idempotent payment: same idempotencyKey returns the same intent", async ({ request }) => {
    const owner = await register(request, "Idem Owner");
    const member = await register(request, "Idem Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);

    const key = `idem-${membership.id}-${Date.now()}`;
    const first = await request.post(`payments/members/${membership.id}/intent`, {
      ...auth(member.token),
      data: { idempotencyKey: key },
    });
    expect(first.status()).toBe(201);
    const firstBody = await first.json();

    const second = await request.post(`payments/members/${membership.id}/intent`, {
      ...auth(member.token),
      data: { idempotencyKey: key },
    });
    expect(second.status()).toBe(201);
    const secondBody = await second.json();
    expect(secondBody.id).toBe(firstBody.id); // same intent, no double charge
  });

  test("cannot join the same room twice", async ({ request }) => {
    const owner = await register(request, "Dup Owner");
    const member = await register(request, "Dup Member");
    const room = await createDigitalRoom(request, owner.token);
    await joinRoom(request, member.token, room.id);

    const again = await request.post(`rooms/${room.id}/members`, {
      ...auth(member.token),
      data: { consentAccepted: true },
    });
    expect(again.status()).toBeGreaterThanOrEqual(400);
    expect(again.status()).toBeLessThan(500);
  });

  test("owner cannot join their own room", async ({ request }) => {
    const owner = await register(request, "Self Owner");
    const room = await createDigitalRoom(request, owner.token);
    const res = await request.post(`rooms/${room.id}/members`, {
      ...auth(owner.token),
      data: { consentAccepted: true },
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });

  test("room creation is rate limited per user (429 after the cap)", async ({ request }) => {
    const owner = await register(request, "RateLimit Owner");
    const body = {
      serviceId: 2, tariffPlanId: 2, categoryId: 1, roomType: "DIGITAL",
      title: "RL room", maxMembers: 4, priceTotal: 7290, pricePerMember: 1822.5,
      currency: "KZT", periodType: "MONTHLY", startDate: "2027-01-01T00:00:00",
    };
    // The cap is 10 creates / 10 min per user.
    for (let i = 0; i < 10; i++) {
      const ok = await request.post(`rooms`, { ...auth(owner.token), data: body });
      expect(ok.status(), `create #${i + 1}`).toBe(201);
    }
    const blocked = await request.post(`rooms`, { ...auth(owner.token), data: body });
    expect(blocked.status()).toBe(429);
  });
});

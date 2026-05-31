import { test, expect } from "@playwright/test";
import { register, auth, createDigitalRoom, joinRoom, pay } from "./helpers";

test.describe("Usage & payouts", () => {
  test("successful member payment creates a PENDING owner payout (minus platform fee)", async ({ request }) => {
    const owner = await register(request, "Payout Owner");
    const member = await register(request, "Payout Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);
    await pay(request, member.token, membership.id);

    const res = await request.get(`payouts/me`, auth(owner.token));
    expect(res.status()).toBe(200);
    const payouts = await res.json();
    expect(Array.isArray(payouts)).toBe(true);
    const forRoom = payouts.find((p: any) => p.roomId === room.id);
    expect(forRoom, "owner has a payout for this room").toBeTruthy();
    // 8% platform fee withheld from the owner payout: 1822.5 * 0.92 = 1676.70
    expect(Number(forRoom.amount)).toBeCloseTo(1822.5 * 0.92, 1);
    expect(forRoom.currency).toBe("KZT");
  });

  test("payout method cannot be registered with a card the user does not own (anti-IDOR)", async ({ request }) => {
    const owner = await register(request, "Payout Owner");
    const res = await request.post(`payouts/methods`, {
      ...auth(owner.token),
      data: { providerCardToken: "SOMEONE-ELSES-TOKEN-123", panMask: "**** 4242" },
    });
    // Rejected: not one of the user's own active saved cards.
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });

  test("room auto-activates on first ACTIVE member, then a review is accepted and reputation updates", async ({ request }) => {
    const owner = await register(request, "Rev Owner");
    const member = await register(request, "Rev Member");
    const room = await createDigitalRoom(request, owner.token);
    const membership = await joinRoom(request, member.token, room.id);
    await pay(request, member.token, membership.id);
    await request.patch(`rooms/${room.id}/members/${membership.id}/owner-access`, {
      ...auth(owner.token),
      data: { accessMethod: "invite_link" },
    });
    await request.post(`rooms/${room.id}/members/me/confirm-access`, auth(member.token));

    // Room should now be ACTIVE (auto-activated by the first ACTIVE member).
    const roomNow = await request.get(`rooms/${room.id}`);
    expect((await roomNow.json()).status).toBe("ACTIVE");

    // Member can now review the owner. HTML/script in the text must be stripped server-side.
    const review = await request.post(`reviews`, {
      ...auth(member.token),
      data: { recipientId: owner.id, roomId: room.id, rating: 5, text: "<script>alert(1)</script>Great owner" },
    });
    expect(review.status()).toBe(201);
    const reviewBody = await review.json();
    expect(reviewBody.text).not.toContain("<");
    expect(reviewBody.text).not.toContain("script");
    expect(reviewBody.text).toContain("Great owner");

    // Owner's public reputation reflects the new review.
    const rep = await request.get(`reputation/users/${owner.id}`);
    expect(rep.status()).toBe(200);
    const repBody = await rep.json();
    expect(repBody.reviewsCount).toBeGreaterThanOrEqual(1);
    expect(repBody.averageRating).toBeGreaterThan(0);

    // A second review for the same (author, recipient, room) is rejected (no review farming).
    const dup = await request.post(`reviews`, {
      ...auth(member.token),
      data: { recipientId: owner.id, roomId: room.id, rating: 1, text: "dup" },
    });
    expect(dup.status()).toBeGreaterThanOrEqual(400);
    expect(dup.status()).toBeLessThan(500);
  });

  test("reputation endpoint is public and returns a baseline for a new user", async ({ request }) => {
    const user = await register(request, "Fresh User");
    const res = await request.get(`reputation/users/${user.id}`);
    expect(res.status()).toBe(200);
    const rep = await res.json();
    expect(rep.userId).toBe(user.id);
    expect(rep.reviewsCount).toBe(0);
  });
});

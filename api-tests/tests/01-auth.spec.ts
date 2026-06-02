import { test, expect } from "@playwright/test";
import { register, unique, auth } from "./helpers";

const json = { "Content-Type": "application/json" };

test.describe("Auth & registration", () => {
  test("happy path: register creates user, no tokens until email verified", async ({ request }) => {
    const u = unique();
    const res = await request.post(`auth/register`, {
      data: { email: u.email, password: u.password, displayName: "Aidar", phone: u.phone },
      headers: json,
    });
    expect(res.status()).toBe(201);
    const body = await res.json();
    // Registration is email-verification gated: no tokens are issued on register.
    expect(body.accessToken).toBeFalsy();
    expect(body.refreshToken).toBeFalsy();
    expect(body.user.email).toBe(u.email);
    expect(body.user.phoneVerified).toBe(false);
    expect(body.user.role).toBe("USER");
    expect(body.user.status).toBe("ACTIVE");
  });

  test("duplicate email → 409", async ({ request }) => {
    const u = unique();
    const first = await request.post(`auth/register`, {
      data: { email: u.email, password: u.password, displayName: "A", phone: u.phone },
      headers: json,
    });
    expect(first.status()).toBe(201);
    const dup = await request.post(`auth/register`, {
      data: { email: u.email, password: u.password, displayName: "A", phone: unique().phone },
      headers: json,
    });
    expect(dup.status()).toBe(409);
  });

  test("invalid email → 400", async ({ request }) => {
    const u = unique();
    const res = await request.post(`auth/register`, {
      data: { email: "not-an-email", password: u.password, displayName: "A", phone: u.phone },
      headers: json,
    });
    expect(res.status()).toBe(400);
  });

  test("invalid phone format → 400", async ({ request }) => {
    const u = unique();
    const res = await request.post(`auth/register`, {
      data: { email: u.email, password: u.password, displayName: "A", phone: "8700123" },
      headers: json,
    });
    expect(res.status()).toBe(400);
  });

  test("login then refresh rotates tokens", async ({ request }) => {
    const user = await register(request);
    const login = await request.post(`auth/login`, {
      data: { email: user.email, password: "Test1234" },
      headers: json,
    });
    expect(login.status()).toBe(200);
    const loginBody = await login.json();
    expect(loginBody.accessToken).toBeTruthy();

    const refresh = await request.post(`auth/refresh`, {
      data: { refreshToken: loginBody.refreshToken },
      headers: json,
    });
    expect(refresh.status()).toBe(200);
    const refreshed = await refresh.json();
    expect(refreshed.accessToken).toBeTruthy();
    expect(refreshed.refreshToken).not.toBe(loginBody.refreshToken); // rotation
  });

  test("reusing a rotated refresh token revokes the whole session", async ({ request }) => {
    const user = await register(request);
    const login = await (await request.post(`auth/login`, {
      data: { email: user.email, password: "Test1234" }, headers: json,
    })).json();

    // Rotate once — old token is revoked, new token issued.
    const rotate = await request.post(`auth/refresh`, {
      data: { refreshToken: login.refreshToken }, headers: json,
    });
    expect(rotate.status()).toBe(200);
    const rotated = await rotate.json();

    // Reusing the OLD (revoked) token must fail...
    const reuse = await request.post(`auth/refresh`, {
      data: { refreshToken: login.refreshToken }, headers: json,
    });
    expect(reuse.status()).toBeGreaterThanOrEqual(400);

    // ...and it must have revoked the WHOLE session, so the new token is dead too.
    const afterReuse = await request.post(`auth/refresh`, {
      data: { refreshToken: rotated.refreshToken }, headers: json,
    });
    expect(afterReuse.status()).toBeGreaterThanOrEqual(400);
  });

  test("login with wrong password → 401", async ({ request }) => {
    const user = await register(request);
    const res = await request.post(`auth/login`, {
      data: { email: user.email, password: "WrongPass99" },
      headers: json,
    });
    expect(res.status()).toBe(401);
  });

  test("/users/me requires a token", async ({ request }) => {
    const noAuth = await request.get(`users/me`);
    expect([401, 403]).toContain(noAuth.status());

    const user = await register(request);
    const me = await request.get(`users/me`, auth(user.token));
    expect(me.status()).toBe(200);
    expect((await me.json()).email).toBe(user.email);
  });
});

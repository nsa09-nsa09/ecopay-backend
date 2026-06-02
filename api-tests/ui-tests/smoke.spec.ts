import { test, expect } from "@playwright/test";

// Browser smoke tests for the critical entry points. Selectors are chosen to be
// resilient to i18n (target input types / submit button / URL), not visible text.
function unique() {
  const base = (Date.now() % 1_000_000_000) * 10 + Math.floor(Math.random() * 10);
  const digits = String(base).slice(-9).padStart(9, "0");
  return { email: `ui_${base}@test.kz`, phone: `+77${digits}` };
}

test.describe("UI smoke", () => {
  test("app loads and renders the login screen", async ({ page }) => {
    await page.goto("/login");
    await expect(page.locator('input[type="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
  });

  test("registration via the UI sends a code and routes to phone verification", async ({ page }) => {
    const u = unique();
    await page.goto("/register");

    // displayName is the first text field; email/phone/password by input type.
    await page.locator("form input").first().fill("UI Tester");
    await page.locator('input[type="email"]').fill(u.email);
    await page.locator('input[type="tel"]').fill(u.phone);
    await page.locator('input[type="password"]').fill("Test1234");

    const submit = page.locator('button[type="submit"]');
    await expect(submit).toBeEnabled();
    await submit.click();

    // On success the app navigates to the phone-verification step.
    await expect(page).toHaveURL(/verify-phone/, { timeout: 15_000 });
  });

  test("catalog/landing is reachable without auth", async ({ page }) => {
    const res = await page.goto("/");
    expect(res?.status() ?? 200).toBeLessThan(400);
    // The SPA shell mounts (root not empty).
    await expect(page.locator("#root")).not.toBeEmpty();
  });
});

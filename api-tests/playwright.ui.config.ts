import { defineConfig } from "@playwright/test";

// Browser-level UI smoke tests. Requires the frontend dev server running
// (pnpm dev → usually http://localhost:5174) pointed at the local backend via .env.local.
export default defineConfig({
  testDir: "./ui-tests",
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    // Dev server must run on :5173 (the only origin the backend CORS allows in dev).
    baseURL: process.env.UI_BASE ?? "http://localhost:5173",
    headless: true,
    // Use the full Chromium build (installed) rather than the headless-shell.
    channel: "chromium",
    screenshot: "only-on-failure",
    viewport: { width: 1280, height: 800 },
  },
});

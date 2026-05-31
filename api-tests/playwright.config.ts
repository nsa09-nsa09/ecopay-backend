import { defineConfig } from "@playwright/test";

// API-only E2E: no browser needed. Hits a running backend over HTTP.
// Override target with API_BASE (default = local docker backend).
export default defineConfig({
  testDir: "./tests",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false, // chain tests share sequential state; keep deterministic
  workers: 1,
  reporter: [["list"]],
  use: {
    // NOTE: trailing slash is required so that relative paths like `auth/register`
    // resolve to `/api/v1/auth/register` (a leading slash would drop the /api/v1 prefix).
    baseURL: process.env.API_BASE ?? "http://localhost:8080/api/v1/",
    extraHTTPHeaders: { "Content-Type": "application/json" },
  },
});

// @ts-check
const { defineConfig, devices } = require('@playwright/test');

/**
 * Playwright E2E configuration for Break-Window Response (§10).
 * Enforces single-box self-contained test execution against deterministic fixtures.
 */
module.exports = defineConfig({
  testDir: './e2e',
  timeout: 30 * 1000,
  expect: {
    timeout: 5000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'env BWR_ENV=test clojure -M -m bwr.main --seed',
    url: 'http://localhost:3000/health',
    reuseExistingServer: false,
    timeout: 60 * 1000,
  },
});

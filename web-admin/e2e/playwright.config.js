// Playwright E2E config for the Taarifu admin console.
// Prereqs: backend on :8081 (dev profile, TAARIFU_MFA_ENFORCED=false) + `ng serve`.
// Override the dev-server port via E2E_BASE_URL (the app's default is :4200).
module.exports = {
  testDir: __dirname,
  testMatch: '**/*.spec.js',
  timeout: 150000,
  expect: { timeout: 15000 },
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:4200',
    headless: true,
    viewport: { width: 1366, height: 900 },
    actionTimeout: 20000,
    navigationTimeout: 30000,
    screenshot: 'on',
  },
};

// Playwright config for screenshotting the freshly-BUILT dist (search-fix) served on :4301.
//
// WHY a separate config: the production `ng build` bundles the absolute API base (http://localhost:8081/api/v1
// from environment.ts — the `development` build config has no env fileReplacement), so a browser loading the
// app from http://localhost:4301 makes a CROSS-ORIGIN XHR the backend's dev CORS allow-list (4200/4300 only)
// rejects. That CORS gap is a dev-origin artefact, not an app defect — the login + every API works server-side
// (verified via curl through the same proxy). To capture the rebuilt UI without restarting the frozen :4300
// dev server or touching backend CORS, we launch Chromium with web-security disabled FOR THIS CAPTURE ONLY.
// This flag lives in the harness, never in the app or server.
module.exports = {
  testDir: __dirname,
  testMatch: '**/phase2-web-admin-v2.spec.js',
  timeout: 240000,
  expect: { timeout: 15000 },
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:4301',
    headless: true,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 20000,
    navigationTimeout: 30000,
    screenshot: 'on',
    launchOptions: {
      args: ['--disable-web-security', '--disable-features=IsolateOrigins,site-per-process'],
    },
  },
};

// @ts-check
// Phase-2 UI screenshot capture for the redesigned (elegant Bootstrap) Taarifu admin console.
//
// One ordered test drives the whole journey so the auth session + theme state carry across pages:
//   login -> dashboard (light, charts) -> dashboard (dark) -> every sidebar area.
// Full-page PNGs land in d:\My_Works\TAARIFU\e2e-screenshots\phase2-web\ (numbered 01..NN).
//
// Auth: dev DevAdminSeeder account, MFA OFF in the dev profile (the /auth/login/password call
// returns tokens directly — verified live), so password login is enough; no TOTP step needed.
const { test, expect } = require('@playwright/test');
const path = require('path');

const PHONE = '+255700000001';
const PASSWORD = 'Admin@12345';

// Absolute output dir (task requirement). Created by the runner; Playwright also mkdirs on write.
const SHOT_DIR = 'd:\\My_Works\\TAARIFU\\e2e-screenshots\\phase2-web';
const shot = (name) => path.join(SHOT_DIR, name);

// Sidebar areas reachable by the ROOT dev-admin (mirrors shell.component.ts sections). Numbered 04+.
const AREAS = [
  ['/reports', '04-reports'],
  ['/moderation', '05-moderation'],
  ['/responders', '06-responders'],
  ['/geography/regions', '07-geography-regions'],
  ['/representatives', '08-representatives'],
  ['/parties', '09-parties'],
  ['/announcements', '10-announcements'],
  ['/issue-categories', '11-issue-categories'],
  ['/institutions/parliaments', '12-institutions-parliaments'],
  ['/tokens', '13-tokens'],
  ['/users', '14-users'],
];

/** Settle helper: wait for network idle (best-effort) then a fixed beat for Bootstrap transitions + data. */
async function settle(page, ms = 1200) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(ms);
}

test('capture Phase-2 elegant UI screenshots', async ({ page }) => {
  test.setTimeout(240000);

  // Surface browser console errors so they show up in the report (and our final summary).
  const consoleErrors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => consoleErrors.push('pageerror: ' + err.message));

  // ---- 01 Login ----
  await page.goto('/');
  await page.waitForSelector('#accountKey', { timeout: 60000 });
  await settle(page, 800);
  await page.screenshot({ path: shot('01-login.png'), fullPage: true });

  // ---- Authenticate ----
  await page.fill('#accountKey', PHONE);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForURL('**/dashboard', { timeout: 30000 });

  // i18n loaded (regression guard for the NG0200 DI-cycle bug): translated labels, not raw keys.
  await expect(page.getByText('auth.loginButton')).toHaveCount(0);

  // ---- 02 Dashboard (light) — wait for KPI charts to actually render ----
  await settle(page, 1500);
  // Charts draw onto <canvas>; wait until at least one canvas exists before shooting.
  await page.waitForSelector('canvas', { timeout: 20000 }).catch(() => {});
  const canvasCount = await page.locator('canvas').count();
  await page.waitForTimeout(1200); // let Chart.js finish its draw animation
  await page.screenshot({ path: shot('02-dashboard.png'), fullPage: true });

  // ---- 03 Dashboard (DARK) — toggle via the topbar theme button ----
  // The theme toggle is the topbar icon button whose aria-label is the localized "dark/light theme".
  const themeBtn = page.locator('.app-topbar .app-topbar__icon-btn').last();
  await themeBtn.click();
  await page.waitForTimeout(1500); // theme CSS vars + chart recolor
  await page.screenshot({ path: shot('03-dashboard-dark.png'), fullPage: true });
  // Restore light theme so the rest of the captures are in the default look.
  await themeBtn.click();
  await page.waitForTimeout(600);

  // ---- 04+ Each sidebar area ----
  const written = ['01-login.png', '02-dashboard.png', '03-dashboard-dark.png'];
  for (const [route, name] of AREAS) {
    const resp = await page.goto(route).catch(() => null);
    // Skip any area that hard-404s at the server (the SPA serves 200 for client routes, so this
    // mostly guards genuinely missing pages).
    const status = resp ? resp.status() : 0;
    await settle(page, 1400);
    // Guard: still authenticated (shell present), not bounced to login.
    const onLogin = page.url().includes('/login');
    const shellCount = await page.locator('#app-sidebar').count();
    if (onLogin) {
      console.log(`SKIP ${route}: bounced to login`);
      continue;
    }
    // Let any charts on the page render too.
    await page.waitForSelector('canvas', { timeout: 2500 }).catch(() => {});
    await page.screenshot({ path: shot(`${name}.png`), fullPage: true });
    written.push(`${name}.png`);
    console.log(`SHOT ${name}.png  route=${route} status=${status} shell=${shellCount}`);
  }

  console.log('CANVAS_ON_DASHBOARD=' + canvasCount);
  console.log('WRITTEN_COUNT=' + written.length);
  console.log('WRITTEN_FILES=' + written.join(','));
  if (consoleErrors.length) {
    console.log('CONSOLE_ERRORS(' + consoleErrors.length + '):');
    for (const e of consoleErrors.slice(0, 40)) console.log('  ! ' + e);
  } else {
    console.log('CONSOLE_ERRORS=0');
  }
});

// @ts-check
// Phase-2 (waves 2 & 4) web-admin screenshot capture for the elegant Bootstrap admin console.
//
// One ordered test drives the whole journey so the auth session + theme carry across pages. It targets the
// NEW Phase-2 surfaces specifically:
//   01 global SEARCH (topbar typeahead + full results page)
//   02 PRIVACY / DSR admin console (data-subject-request queue)
//   03 PAYMENTS admin view (mobile-money ledger + totals strip; MSISDN masked)
//   04 DASHBOARD analytics date-range picker / drill-down (refreshed charts)
//   05 supplementary captures (search results page, dark dashboard)
//
// Full-page PNGs (viewport 1440x900) land in d:\My_Works\TAARIFU\e2e-screenshots\phase2-web-admin-v2\.
//
// Auth: dev DevAdminSeeder account, MFA OFF in the dev profile, so password login is enough (no TOTP).
// Every Phase-2 view is built to DEGRADE GRACEFULLY when its server endpoint is absent/empty in dev (PRD §15),
// so this spec captures whatever state renders (data, empty, or error/retry) and never hard-fails on emptiness.
const { test, expect } = require('@playwright/test');
const path = require('path');

const PHONE = '+255700000001';
const PASSWORD = 'Admin@12345';

// Absolute output dir (task requirement). Playwright mkdirs on write; we also create it in the runner.
const SHOT_DIR = 'd:\\My_Works\\TAARIFU\\e2e-screenshots\\phase2-web-admin-v2';
const shot = (name) => path.join(SHOT_DIR, name);

/** Settle: best-effort network idle then a fixed beat for Bootstrap transitions + chart draw. */
async function settle(page, ms = 1200) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(ms);
}

test('capture Phase-2 web-admin v2 screenshots', async ({ page }) => {
  test.setTimeout(240000);

  // Task viewport: 1440x900.
  await page.setViewportSize({ width: 1440, height: 900 });

  // Surface browser console errors for the final summary.
  const consoleErrors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => consoleErrors.push('pageerror: ' + err.message));

  const written = [];
  const notes = [];

  // ---- Authenticate ----
  await page.goto('/');
  await page.waitForSelector('#accountKey', { timeout: 60000 });
  await page.fill('#accountKey', PHONE);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForURL('**/dashboard', { timeout: 30000 });
  // i18n loaded (regression guard for the NG0200 DI-cycle bug): translated labels, not raw keys.
  await expect(page.getByText('auth.loginButton')).toHaveCount(0);
  notes.push('LOGIN: ok (landed on /dashboard, i18n resolved)');

  // ============================================================
  // 01 — GLOBAL SEARCH (topbar typeahead). Type a term, capture the open dropdown / empty panel.
  // ============================================================
  const searchInput = page.locator('#global-search-input');
  await searchInput.click();
  await searchInput.fill('Dar');
  // Debounce is 300ms; wait for the request + render of the panel (results OR the "no results" line).
  await page.waitForTimeout(1500);
  await settle(page, 600);
  const panelVisible = await page.locator('#global-search-panel').isVisible().catch(() => false);
  const hitCount = await page.locator('#global-search-panel .topbar-search__hit').count().catch(() => 0);
  await page.screenshot({ path: shot('01-search-typeahead.png'), fullPage: true });
  written.push('01-search-typeahead.png');
  notes.push(
    `01 SEARCH typeahead: panel ${panelVisible ? 'open' : 'not shown'}, hits=${hitCount}` +
      (hitCount === 0 ? ' (empty state — dev search index has no public reps/orgs/reports)' : ''),
  );

  // Full results page via Enter.
  await searchInput.press('Enter');
  await page.waitForURL('**/search**', { timeout: 15000 }).catch(() => {});
  await settle(page, 1200);
  await page.screenshot({ path: shot('01b-search-results-page.png'), fullPage: true });
  written.push('01b-search-results-page.png');
  notes.push('01b SEARCH results page: ' + page.url());

  // ============================================================
  // 02 — PRIVACY / DSR admin console.
  // ============================================================
  await page.goto('/privacy');
  await settle(page, 1600);
  const dsrTitle = await page.locator('.page-header__title').first().textContent().catch(() => '');
  const dsrErr = await page.locator('app-state-panel[kind="error"], .state-panel--error').count().catch(() => 0);
  const dsrEmpty = await page.locator('app-state-panel[kind="empty"], .state-panel--empty').count().catch(() => 0);
  const dsrRows = await page.locator('tbody tr').count().catch(() => 0);
  await page.screenshot({ path: shot('02-privacy-dsr.png'), fullPage: true });
  written.push('02-privacy-dsr.png');
  notes.push(
    `02 PRIVACY/DSR: title="${(dsrTitle || '').trim()}" ` +
      `state=${dsrErr ? 'error/retry' : dsrEmpty ? 'empty' : dsrRows ? `data(${dsrRows} rows)` : 'unknown'}`,
  );

  // ============================================================
  // 03 — PAYMENTS admin (mobile-money ledger + totals; MSISDN masked).
  // ============================================================
  await page.goto('/payments');
  await settle(page, 1600);
  const payTitle = await page.locator('.page-header__title').first().textContent().catch(() => '');
  const payTotals = await page.locator('.stat-tile').count().catch(() => 0);
  const payErr = await page.locator('app-state-panel[kind="error"], .state-panel--error').count().catch(() => 0);
  const payEmpty = await page.locator('app-state-panel[kind="empty"], .state-panel--empty').count().catch(() => 0);
  const payRows = await page.locator('tbody tr').count().catch(() => 0);
  await page.screenshot({ path: shot('03-payments.png'), fullPage: true });
  written.push('03-payments.png');
  notes.push(
    `03 PAYMENTS: title="${(payTitle || '').trim()}" totalsTiles=${payTotals} ` +
      `state=${payErr ? 'error/retry' : payEmpty ? 'empty' : payRows ? `data(${payRows} rows)` : 'unknown'}`,
  );

  // ============================================================
  // 04 — DASHBOARD analytics date-range picker + drill-down. Refresh charts.
  // ============================================================
  await page.goto('/dashboard');
  await settle(page, 1500);
  await page.waitForSelector('canvas', { timeout: 20000 }).catch(() => {});
  const canvasCount = await page.locator('canvas').count();
  await page.waitForTimeout(1200); // let Chart.js finish drawing

  // Drive the date-range picker: switch to the 7-day window (re-fetches every window-scoped chart).
  const range7 = page.getByRole('button', { name: /^7\s*d|7 days/i }).first();
  const range7Exists = await range7.count();
  if (range7Exists) {
    await range7.click().catch(() => {});
    await settle(page, 1400);
  }
  // Flip the reports-volume drill-down to "by area" to show the drill-down interaction.
  const byArea = page.getByRole('button', { name: /by area|area/i }).first();
  if (await byArea.count()) {
    await byArea.click().catch(() => {});
    await page.waitForTimeout(800);
  }
  await page.screenshot({ path: shot('04-dashboard-daterange-7d.png'), fullPage: true });
  written.push('04-dashboard-daterange-7d.png');

  // Switch to the 90-day window and back to the default by-category drill — second analytics capture.
  const range90 = page.getByRole('button', { name: /^90\s*d|90 days/i }).first();
  if (await range90.count()) {
    await range90.click().catch(() => {});
    await settle(page, 1400);
  }
  const byCategory = page.getByRole('button', { name: /by category|category/i }).first();
  if (await byCategory.count()) {
    await byCategory.click().catch(() => {});
    await page.waitForTimeout(800);
  }
  await page.waitForTimeout(1000);
  await page.screenshot({ path: shot('04b-dashboard-daterange-90d.png'), fullPage: true });
  written.push('04b-dashboard-daterange-90d.png');
  notes.push(`04 DASHBOARD: canvases=${canvasCount}, drove date-range (7D→90D) + volume drill-down toggle`);

  // Custom range: pick CUSTOM to reveal the from/to date inputs (the drill-down picker proper).
  const customBtn = page.getByRole('button', { name: /custom/i }).first();
  if (await customBtn.count()) {
    await customBtn.click().catch(() => {});
    await page.waitForTimeout(500);
    const fromInput = page.locator('#range-from');
    const toInput = page.locator('#range-to');
    if (await fromInput.count()) {
      await fromInput.fill('2026-01-01').catch(() => {});
      await toInput.fill('2026-06-25').catch(() => {});
      await settle(page, 1400);
    }
    await page.screenshot({ path: shot('04c-dashboard-custom-range.png'), fullPage: true });
    written.push('04c-dashboard-custom-range.png');
    notes.push('04c DASHBOARD: custom date-range inputs (from/to) revealed + applied');
  }

  // ============================================================
  // 05 — Dark-theme dashboard (refreshed charts in the alternate look).
  // ============================================================
  const themeBtn = page.locator('.app-topbar .app-topbar__icon-btn').last();
  await themeBtn.click().catch(() => {});
  await page.waitForTimeout(1500); // theme CSS vars + chart recolor
  await page.screenshot({ path: shot('05-dashboard-dark.png'), fullPage: true });
  written.push('05-dashboard-dark.png');
  await themeBtn.click().catch(() => {}); // restore light
  notes.push('05 DASHBOARD (dark): theme toggle + chart recolor');

  // ---- Summary to stdout (parsed by the runner). ----
  console.log('=== SUMMARY ===');
  for (const n of notes) console.log('NOTE ' + n);
  console.log('WRITTEN_COUNT=' + written.length);
  console.log('WRITTEN_FILES=' + written.join(','));
  if (consoleErrors.length) {
    console.log('CONSOLE_ERRORS(' + consoleErrors.length + '):');
    for (const e of consoleErrors.slice(0, 50)) console.log('  ! ' + e);
  } else {
    console.log('CONSOLE_ERRORS=0');
  }
});

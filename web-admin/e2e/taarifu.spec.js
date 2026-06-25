const { test, expect } = require('@playwright/test');

// Local dev-admin (created by the @Profile("dev") DevAdminSeeder; MFA off via TAARIFU_MFA_ENFORCED=false).
const PHONE = '+255700000001';
const PASSWORD = 'Admin@12345';

// Every admin area the ROOT dev-admin reaches (from shell.component.html routerLinks).
const AREAS = [
  ['/dashboard', 'dashboard'],
  ['/reports', 'reports'],
  ['/moderation', 'moderation'],
  ['/responders', 'responders'],
  ['/geography/regions', 'geography-regions'],
  ['/representatives', 'representatives'],
  ['/parties', 'parties'],
  ['/announcements', 'announcements'],
  ['/issue-categories', 'issue-categories'],
  ['/institutions/parliaments', 'institutions'],
  ['/tokens', 'tokens'],
  ['/users', 'users'],
];

async function login(page) {
  await page.goto('/');
  await page.waitForSelector('#accountKey', { timeout: 30000 });
  await page.fill('#accountKey', PHONE);
  await page.fill('#password', PASSWORD);
  await page.click('button[type="submit"]');
}

test('login succeeds (MFA off) and lands on the dashboard', async ({ page }) => {
  await login(page);
  await page.waitForURL('**/dashboard', { timeout: 30000 });
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: 'shots/02-dashboard.png', fullPage: true });
  expect(page.url()).toContain('/dashboard');
  // i18n loaded (regression guard for the NG0200 DI-cycle bug): labels are translated, not raw keys.
  await expect(page.getByText('auth.loginButton')).toHaveCount(0);
});

test('traverse every admin area without an auth bounce or crash', async ({ page }) => {
  test.setTimeout(150000);
  await login(page);
  await page.waitForURL('**/dashboard', { timeout: 30000 });
  let i = 3;
  for (const [route, name] of AREAS) {
    await page.goto(route);
    await page.waitForLoadState('networkidle').catch(() => {});
    expect.soft(page.url(), `${route} kept the session`).not.toContain('/login');
    expect.soft(await page.locator('#app-sidebar').count(), `${route} rendered the shell`).toBeGreaterThan(0);
    await page.screenshot({ path: `shots/${String(i).padStart(2, '0')}-${name}.png`, fullPage: true });
    i++;
  }
});

test('seeded reference data loads into the UI (geography regions)', async ({ page }) => {
  await login(page);
  await page.waitForURL('**/dashboard', { timeout: 30000 });
  await page.goto('/geography/regions');
  await page.waitForLoadState('networkidle').catch(() => {});
  await expect.soft(
    page.getByText(/Dar es Salaam|Dodoma|Arusha|Kilimanjaro|Mwanza/i).first()
  ).toBeVisible({ timeout: 15000 });
});

test('ROOT reaches staff-gated areas (RoleHierarchy regression guard)', async ({ page }) => {
  await login(page);
  await page.waitForURL('**/dashboard', { timeout: 30000 });
  // ROOT > ADMIN > MODERATOR: the admin/moderator nav must be visible and the dashboard stats must load
  // (no `common.forbidden` toast) — guards the missing-RoleHierarchy bug found via E2E.
  await page.waitForLoadState('networkidle');
  await expect(page.locator('a[href="/moderation"]')).toBeVisible();
  await expect(page.locator('a[href="/users"]')).toBeVisible();
  await expect(page.getByText('common.forbidden')).toHaveCount(0);
});

test('wrong password is rejected (auth actually enforced)', async ({ page }) => {
  await page.goto('/');
  await page.waitForSelector('#accountKey', { timeout: 30000 });
  await page.fill('#accountKey', PHONE);
  await page.fill('#password', 'definitely-wrong');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(3500);
  expect(page.url()).not.toContain('/dashboard');
});

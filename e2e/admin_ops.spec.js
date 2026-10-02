// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * Helper to log in as an authorized admin operator via the single-use magic-link flow (§9.2).
 * Navigates through the scanner-safe confirmation page to obtain a valid bwr_session cookie.
 */
async function loginAsAdmin(page, request) {
  const mintResp = await request.post('/test/auth/magic-link', {
    data: { identity: 'head-coach@breakwindow.lan' },
  });
  expect(mintResp.ok()).toBeTruthy();
  const { magicLinkUrl } = await mintResp.json();
  await page.goto(magicLinkUrl);
  await page.locator('#confirm-signin-btn').click();
  await page.waitForURL('**/admin');
}

test.describe('Admin Operations, Magic-Link Authentication & Security Contours (§8, §9, §10)', () => {
  test('magic-link authentication flow: GET confirm sign-in -> POST consume -> /admin access (§9.2, §10)', async ({ page, request }) => {
    // 1. Mint a fresh magic link via the test-only endpoint (BWR_ENV=test)
    const mintResp = await request.post('/test/auth/magic-link', {
      data: { identity: 'head-coach@breakwindow.lan' },
    });
    expect(mintResp.ok()).toBeTruthy();
    const mintData = await mintResp.json();
    expect(mintData.status).toBe('ok');
    const magicLinkUrl = mintData.magicLinkUrl;
    expect(magicLinkUrl).toContain('/auth/verify?token=');

    // 2. GET /auth/verify renders "Confirm sign-in" page without consuming the token (§2)
    await page.goto(magicLinkUrl);
    await expect(page.locator('h1')).toContainText('Confirm Sign-in');
    const confirmBtn = page.locator('#confirm-signin-btn');
    await expect(confirmBtn).toBeVisible();
    await expect(confirmBtn).toContainText('Confirm sign-in');

    // 3. User clicks "Confirm sign-in" which POSTs the token to /auth/verify
    await confirmBtn.click();

    // 4. Server consumes token, sets bwr_session cookie, and redirects to /admin
    await page.waitForURL('**/admin');
    await expect(page.locator('h1')).toContainText('Strategy & Operations Administration');
    await expect(page.locator('.admin-operator-badge')).toContainText('head-coach@breakwindow.lan');
    await expect(page.locator('.match-meta')).toContainText('XTDB 1.x (Bi-temporal)');
  });

  test('rule hot-reload through the dashboard form updates timestamp and displays success alert (§7, §8)', async ({ page, request }) => {
    await loginAsAdmin(page, request);

    // Locate rule row in Declarative Rule Engine Status table
    const ruleRow = page.locator('tr', { hasText: 'rule/break-window-substitution-pattern' });
    await expect(ruleRow).toBeVisible();

    // Capture the "Last Reloaded" cell text before triggering hot-reload
    const lastReloadedCell = ruleRow.locator('td').nth(3);
    const beforeTimestamp = await lastReloadedCell.innerText();

    // Click "Hot Reload" button inside the rendered form (exercises real browser CSRF token)
    const hotReloadBtn = ruleRow.locator('button', { hasText: 'Hot Reload' });
    await expect(hotReloadBtn).toBeVisible();
    await hotReloadBtn.click();

    // Assert flash success message
    await expect(page.locator('.alert-success')).toContainText('hot-reloaded successfully');

    // Assert that the "Last Reloaded" cell text changed from its pre-reload state
    const afterTimestamp = await lastReloadedCell.innerText();
    expect(afterTimestamp).not.toBe(beforeTimestamp);
    expect(afterTimestamp).toContain('UTC');
  });

  test('data-quality override through the dashboard form updates tier and reflects on public view (§8, §9)', async ({ page, request }) => {
    await loginAsAdmin(page, request);

    try {
      // 1. Select match M42, choose 'unverified', fill mandatory audit reason, submit form
      await page.selectOption('#match-select', 'M42');
      await page.selectOption('#quality-select', 'unverified');
      await page.fill('#reason-input', 'Audited by head coach analyst during automated E2E test');
      await page.locator('#data-quality-form button[type="submit"]').click();

      // Assert flash success message
      await expect(page.locator('.alert-success')).toContainText('data quality overridden to unverified');

      // 2. Verify public match view immediately reflects 'Unverified Data' badge
      await page.goto('/matches/M42');
      await expect(page.locator('.badge-unverified')).toContainText('Unverified Data');
    } finally {
      // 3. Shared-state cleanup: always restore match M42 to 'verified' even if assertions fail
      await page.goto('/admin');
      if (await page.locator('#match-select').isVisible()) {
        await page.selectOption('#match-select', 'M42');
        await page.selectOption('#quality-select', 'verified');
        await page.fill('#reason-input', 'Restored verified tier post-audit cleanup');
        await page.locator('#data-quality-form button[type="submit"]').click();
        await expect(page.locator('.alert-success')).toContainText('data quality overridden to verified');
      }

      // Verify public match report badge is back to 'Verified Data'
      await page.goto('/matches/M42');
      await expect(page.locator('.badge-verified')).toContainText('Verified Data');
    }
  });

  test('single-use token replay protection renders 401 error page (§9.2, §10)', async ({ page, request }) => {
    // 1. Mint a single-use token
    const mintResp = await request.post('/test/auth/magic-link', {
      data: { identity: 'head-coach@breakwindow.lan' },
    });
    const { magicLinkUrl } = await mintResp.json();

    // 2. First visit & confirm: successfully lands on /admin
    await page.goto(magicLinkUrl);
    await page.locator('#confirm-signin-btn').click();
    await page.waitForURL('**/admin');

    // 3. Second visit (replay attempt with already consumed token):
    await page.goto(magicLinkUrl);
    await page.locator('#confirm-signin-btn').click();

    // 4. Must reject with semantic 401 page
    await expect(page.locator('h1')).toContainText('401 — Authentication Failed');
    await expect(page.locator('.error-message')).toContainText('already been used');
  });

  test('unauthenticated access to /admin is blocked with HTTP 401 (§9.2)', async ({ browser }) => {
    // Isolated browser context without any session cookies
    const context = await browser.newContext();
    const cleanPage = await context.newPage();

    const response = await cleanPage.goto('/admin');
    expect(response?.status()).toBe(401);
    await expect(cleanPage.locator('h1')).toContainText('401 — Unauthorized');
    await expect(cleanPage.locator('.error-message')).toContainText('Valid authenticated session required');

    await context.close();
  });
});

// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Public Match Reports & Auditable Evidence Drilldown (§8, §10)', () => {
  test('homepage lists verified fixtures and links to match report', async ({ page }) => {
    await page.goto('/');

    // Page title and heading
    await expect(page).toHaveTitle(/Break-Window Response/i);
    const mainHeading = page.locator('h1');
    await expect(mainHeading).toContainText('Break-Window Response');

    // Fixture link to France vs Iraq (M42)
    const matchRow = page.locator('tr', { hasText: 'France vs Iraq' });
    await expect(matchRow).toBeVisible();
    const matchLink = matchRow.locator('a[href="/matches/M42"]');
    await expect(matchLink).toBeVisible();
    await expect(matchLink).toContainText('View Match Report →');
  });

  test('match report renders metadata, both scheduled break windows, and discrete event timeline', async ({ page }) => {
    await page.goto('/matches/M42');

    // Heading and match meta
    await expect(page.locator('h1')).toContainText('France vs Iraq');
    await expect(page.locator('.match-meta')).toContainText('Philadelphia Stadium');
    await expect(page.locator('.badge-verified')).toContainText('Verified Data');

    // Both break windows must be present in the Scheduled Break Windows table (§3a)
    const stoppagesTable = page.locator('.data-table').first();
    await expect(stoppagesTable).toContainText('M42-stp-22');
    await expect(stoppagesTable).toContainText("22'");
    await expect(stoppagesTable).toContainText('M42-stp-67');
    await expect(stoppagesTable).toContainText("67'");

    // Timeline must contain discrete named events
    const timeline = page.locator('.data-table').last();
    await expect(timeline.locator('#evt-M42-shot-21')).toBeVisible();
    await expect(timeline.locator('#evt-M42-shot-21')).toContainText('Mbappe');
    await expect(timeline.locator('#evt-M42-sub-23')).toBeVisible();
    await expect(timeline.locator('#evt-M42-sub-23')).toContainText('Griezmann');
    await expect(timeline.locator('#evt-M42-sub-23')).toContainText('Thuram');
    await expect(timeline.locator('#evt-M42-sub-67')).toBeVisible();
    await expect(timeline.locator('#evt-M42-sub-67')).toContainText('Rabiot');
    await expect(timeline.locator('#evt-M42-sub-67')).toContainText('Camavinga');
  });

  test('match report displays recommendations and evidence trails for BOTH 22\' and 67\' windows (§3a)', async ({ page }) => {
    await page.goto('/matches/M42');

    const recBoxes = page.locator('.recommendation-box');
    await expect(recBoxes).toHaveCount(2);

    // 22' window recommendation with evidence M42-sub-23
    const rec22 = recBoxes.filter({ hasText: "M42-sub-23" });
    await expect(rec22).toBeVisible();
    await expect(rec22).toContainText('Rule: break-window-substitution-pattern (v1.0.0)');
    await expect(rec22.locator('a[href*="/stoppages/M42-stp-22#evt-M42-sub-23"]')).toContainText('M42-sub-23');

    // 67' window recommendation with evidence M42-sub-67
    const rec67 = recBoxes.filter({ hasText: "M42-sub-67" });
    await expect(rec67).toBeVisible();
    await expect(rec67).toContainText('Rule: break-window-substitution-pattern (v1.0.0)');
    await expect(rec67.locator('a[href*="/stoppages/M42-stp-67#evt-M42-sub-67"]')).toContainText('M42-sub-67');
  });

  test('stoppage drilldown displays verified evidence entities linking to match events', async ({ page }) => {
    // 1. Drill into 22' stoppage
    await page.goto('/matches/M42/stoppages/M42-stp-22');
    await expect(page.locator('h1')).toContainText("22' Hydration Break Window");
    await expect(page.locator('.match-meta')).toContainText('France vs Iraq');
    await expect(page.locator('.data-table')).toContainText('M42-sub-23');
    await expect(page.locator('.data-table')).toContainText("23'");
    await expect(page.locator('.data-table')).toContainText('Off: Griezmann → On: Thuram');

    // 2. Drill into 67' stoppage
    await page.goto('/matches/M42/stoppages/M42-stp-67');
    await expect(page.locator('h1')).toContainText("67' Hydration Break Window");
    await expect(page.locator('.match-meta')).toContainText('France vs Iraq');
    await expect(page.locator('.data-table')).toContainText('M42-sub-67');
    await expect(page.locator('.data-table')).toContainText("67'");
    await expect(page.locator('.data-table')).toContainText('Off: Rabiot → On: Camavinga');
  });

  test('aggregated team break profile shows cross-match tendencies and break substitution frequency', async ({ page }) => {
    await page.goto('/teams/France/break-profile');

    await expect(page.locator('h1')).toContainText('France — Break-Window Tendency Profile');
    await expect(page.locator('.metric-value').first()).toContainText('1'); // Matches analyzed
    await expect(page.locator('.metric-value').nth(1)).toContainText('2'); // Break window tendencies (22' and 67')
    await expect(page.locator('.metric-value').nth(2)).toContainText('2'); // Break subs triggered

    // Links to match report
    const matchLink = page.locator('a[href="/matches/M42"]').first();
    await expect(matchLink).toBeVisible();
  });
});

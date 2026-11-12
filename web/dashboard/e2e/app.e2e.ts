import { expect, test, type Page } from '@playwright/test';

// The app runs against the MSW mock backend (`dev:mock`); `streamMs` and `tokenMs` tune the mocks.

/** Opens the app and signs in through the dev sign-in screen as the named demo user. */
async function openAs(page: Page, url: string, user = 'Nurse Levi') {
  await page.goto(url);
  await page.getByRole('button', { name: new RegExp(user) }).click();
  await expect(page.getByTestId('current-user')).toContainText(user);
}

test('ward overview lists 20 patients, riskiest first, with live alert updates', async ({
  page,
}) => {
  await openAs(page, '/?streamMs=1500#/');
  const grid = page.getByTestId('ward-grid');
  await expect(grid.getByRole('link')).toHaveCount(20);
  await expect(grid.getByRole('link').first()).toHaveAttribute('href', '#/patients/P-0003');
  await expect(page.getByText('Live alerts connected')).toBeVisible();
  await expect(page.locator('.tile-fresh')).toBeVisible({ timeout: 10_000 });
});

test('patient detail shows six charts and switches range and resolution', async ({ page }) => {
  await openAs(page, '/?streamMs=600000#/');
  await page.getByTestId('tile-P-0003').click();
  await expect(page.getByRole('heading', { name: 'Yossi Mizrahi' })).toBeVisible();
  await expect(page.locator('figure.chart')).toHaveCount(6);
  await expect(page.getByTestId('chart-SPO2')).toContainText('Below normal');
  await page.getByRole('button', { name: '24 hours' }).click();
  await page.getByRole('button', { name: 'Per minute' }).click();
  await expect(page.getByRole('button', { name: 'Per minute' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(page.locator('figure.chart path.ribbon').first()).toBeAttached();
});

test('acknowledging an alert works, and a conflict is explained', async ({ page }) => {
  await openAs(page, '/?streamMs=600000#/alerts');
  const list = page.getByTestId('alert-list');
  const first = list.locator('li').first();
  await first.getByRole('button', { name: 'Acknowledge' }).click();
  await first.getByRole('button', { name: 'Confirm' }).click();
  await expect(first.getByText('Acknowledged by Nurse Levi')).toBeVisible();

  const conflict = page.getByTestId('alert-00000000-0000-4000-8000-0000000000c1');
  await conflict.getByRole('button', { name: 'Acknowledge' }).click();
  await conflict.getByRole('button', { name: 'Confirm' }).click();
  await expect(conflict.getByRole('alert')).toContainText('changed by someone else');
  await expect(conflict.getByText('Acknowledged by dr.levi')).toBeVisible();
});

test('alert feed filters by severity and searches text', async ({ page }) => {
  await openAs(page, '/?streamMs=600000#/alerts');
  await page.getByLabel('Severity').selectOption('CRITICAL');
  await expect(page.getByTestId('alert-list').locator('li').first()).toContainText('Critical');
  await page.getByLabel('Severity').selectOption('');
  await page.getByLabel('Search alerts').fill('hypotension');
  await expect(page.getByText('No alerts match these filters.')).toBeVisible();
  await page.getByLabel('Search alerts').fill('Respiratory');
  await expect(page.getByTestId('alert-list').locator('li')).toHaveCount(1);
});

test('a NEWS2 row expands to its contributing alerts and the resolved section opens', async ({
  page,
}) => {
  await openAs(page, '/?streamMs=600000#/alerts');
  const parent = page.getByTestId('alert-00000000-0000-4000-8000-000000000001');
  await parent.getByRole('button', { name: 'Show 3 contributing alerts' }).click();
  await expect(parent.getByTestId(/^child-/)).toHaveCount(3);
  await page.getByRole('button', { name: 'Resolved (2)' }).click();
  await expect(page.getByTestId(/^resolved-/)).toHaveCount(2);
});

test('one live episode is updated in place, escalates and then resolves', async ({ page }) => {
  await openAs(page, '/?streamMs=700#/alerts');
  const live = page.getByTestId('alert-00000000-0000-4000-8000-000000000100');
  await expect(live).toContainText('P-0009');
  await expect(live.getByLabel('3 occurrences')).toBeVisible();
  await expect(live).toHaveClass(/alert-escalated/);
  await expect(page.getByTestId('alert-00000000-0000-4000-8000-000000000100')).toHaveCount(1);
  await expect(live).toHaveCount(0, { timeout: 10_000 });
  await page.getByRole('button', { name: /^Resolved \(/ }).click();
  await expect(page.getByTestId('resolved-00000000-0000-4000-8000-000000000100')).toBeVisible();
});

test('copilot streams tool calls, citations and a final answer, and reuses the session', async ({
  page,
}) => {
  const bodies: Array<Record<string, unknown>> = [];
  page.on('request', (request) => {
    if (request.url().includes('/copilot-api/api/v1/chat')) {
      bodies.push(request.postDataJSON() as Record<string, unknown>);
    }
  });
  await openAs(page, '/?streamMs=600000&tokenMs=5#/patients/P-0003');
  await page.getByRole('button', { name: 'Copilot' }).click();
  await page.getByLabel('Message to the copilot').fill('How is this patient?');
  await page.getByRole('button', { name: 'Send' }).click();
  await expect(page.getByTestId('tool-call')).toHaveCount(2);
  await expect(page.getByTestId('citation')).toHaveCount(2);
  await expect(page.getByText(/tokens in, 96 out/)).toBeVisible();
  await page.getByLabel('Message to the copilot').fill('And the next step?');
  await page.getByRole('button', { name: 'Send' }).click();
  await expect.poll(() => bodies.length).toBe(2);
  expect(bodies[0].sessionId).toBeUndefined();
  expect(bodies[1].sessionId).toBe('a1b2c3d4e5f60718293a4b5c6d7e8f90');
});

test('keyboard: skip link, tile focus and Enter opens a patient', async ({ page }) => {
  await openAs(page, '/?streamMs=600000#/');
  await expect(page.getByTestId('ward-grid').getByRole('link')).toHaveCount(20);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.getByTestId('tile-P-0007').focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#\/patients\/P-0007$/);
});

test('signed out, only the sign-in screen is shown and the session ends on sign out', async ({
  page,
}) => {
  await page.goto('/?streamMs=600000#/');
  await expect(page.getByRole('heading', { name: 'Sign in to VitalStream' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Alerts' })).toHaveCount(0);
  await page.getByRole('button', { name: /Dr\. Cohen/ }).click();
  await expect(page.getByTestId('current-user')).toContainText('Dr. Cohen');
  const leaked = await page.evaluate(() => {
    const token = JSON.parse(window.sessionStorage.getItem('vitalstream.session') ?? '{}').token;
    return Object.keys(window.localStorage).some((k) =>
      window.localStorage.getItem(k)?.includes(token),
    );
  });
  expect(leaked).toBe(false);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to VitalStream' })).toBeVisible();
});

test('a viewer can read but Acknowledge is disabled', async ({ page }) => {
  await openAs(page, '/?streamMs=600000#/alerts', 'Observer');
  const first = page.getByTestId('alert-list').locator('li').first();
  await expect(first.getByRole('button', { name: 'Acknowledge' })).toBeDisabled();
  await expect(page.getByTestId('current-user')).toContainText('Viewer');
});

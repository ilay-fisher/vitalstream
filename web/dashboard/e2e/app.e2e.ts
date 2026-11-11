import { expect, test } from '@playwright/test';

// The app runs against the MSW mock backend (`dev:mock`); `streamMs` and `tokenMs` tune the mocks.

test('ward overview lists 20 patients, riskiest first, with live alert updates', async ({
  page,
}) => {
  await page.goto('/?streamMs=1500#/');
  const grid = page.getByTestId('ward-grid');
  await expect(grid.getByRole('link')).toHaveCount(20);
  await expect(grid.getByRole('link').first()).toHaveAttribute('href', '#/patients/P-0003');
  await expect(page.getByText('Live alerts connected')).toBeVisible();
  await expect(page.locator('.tile-fresh')).toBeVisible({ timeout: 10_000 });
});

test('patient detail shows six charts and switches range and resolution', async ({ page }) => {
  await page.goto('/?streamMs=600000#/');
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
  await page.goto('/?streamMs=600000#/alerts');
  const list = page.getByTestId('alert-list');
  const first = list.locator('li').first();
  await first.getByRole('button', { name: 'Acknowledge' }).click();
  await first.getByLabel('Acknowledged by').fill('dr.cohen');
  await first.getByRole('button', { name: 'Confirm' }).click();
  await expect(first.getByText('Acknowledged by dr.cohen')).toBeVisible();

  const conflict = page.getByTestId('alert-00000000-0000-4000-8000-0000000000c1');
  await conflict.getByRole('button', { name: 'Acknowledge' }).click();
  await conflict.getByLabel('Acknowledged by').fill('dr.cohen');
  await conflict.getByRole('button', { name: 'Confirm' }).click();
  await expect(conflict.getByRole('alert')).toContainText('changed by someone else');
  await expect(conflict.getByText('Acknowledged by dr.levi')).toBeVisible();
});

test('alert feed filters by severity and searches text', async ({ page }) => {
  await page.goto('/?streamMs=600000#/alerts');
  await page.getByLabel('Severity').selectOption('CRITICAL');
  await expect(page.getByTestId('alert-list').locator('li').first()).toContainText('Critical');
  await page.getByLabel('Severity').selectOption('');
  await page.getByLabel('Search alerts').fill('hypotension');
  await expect(page.getByText('No alerts match these filters.')).toBeVisible();
  await page.getByLabel('Search alerts').fill('Systolic');
  await expect(page.getByTestId('alert-list').locator('li')).toHaveCount(1);
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
  await page.goto('/?streamMs=600000&tokenMs=5#/patients/P-0003');
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
  await page.goto('/?streamMs=600000#/');
  await expect(page.getByTestId('ward-grid').getByRole('link')).toHaveCount(20);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.getByTestId('tile-P-0007').focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#\/patients\/P-0007$/);
});

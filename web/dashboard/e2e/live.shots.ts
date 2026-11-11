import path from 'node:path';
import { test } from '@playwright/test';

// Needs a real copilot: run with LIVE_COPILOT=1 and VITE_COPILOT_URL pointing at it.
test.skip(!process.env.LIVE_COPILOT, 'set LIVE_COPILOT=1 with a running copilot');
test.use({ viewport: { width: 1440, height: 900 } });

test('copilot against the real service (fake LLM)', async ({ page }) => {
  await page.goto('/?streamMs=600000&liveCopilot#/patients/P-0003');
  await page.locator('figure.chart path.series').nth(5).waitFor({ state: 'attached' });
  await page.getByRole('button', { name: 'Copilot' }).click();
  await page.getByLabel('Message to the copilot').fill('How is P-0003 doing?');
  await page.getByRole('button', { name: 'Send' }).click();
  await page.getByTestId('citation').first().waitFor();
  await page.getByText(/tokens in/).waitFor();
  await page.screenshot({
    path: path.resolve(process.cwd(), '../../docs/images/dashboard-copilot-live.png'),
  });
});

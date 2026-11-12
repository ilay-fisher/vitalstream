import path from 'node:path';
import { expect, test, type Page } from '@playwright/test';

const OUT = path.resolve(process.cwd(), '../../docs/images');
const SIZES = {
  light: { viewport: { width: 1440, height: 900 }, colorScheme: 'light' as const },
  dark: { viewport: { width: 1440, height: 900 }, colorScheme: 'dark' as const },
  mobile: { viewport: { width: 390, height: 844 }, colorScheme: 'light' as const },
};

const QUIET = 'streamMs=600000';

async function signIn(page: Page, url: string) {
  await page.goto(url);
  await page.getByRole('button', { name: /Nurse Levi/ }).click();
  await expect(page.getByTestId('current-user')).toBeVisible();
}

async function openCopilotMidStream(page: Page) {
  await page.getByRole('button', { name: 'Copilot' }).click();
  await page
    .getByLabel('Message to the copilot')
    .fill('Summarise the current state of this patient');
  await page.getByRole('button', { name: 'Send' }).click();
  await page.getByText(/Start the sepsis bundle/).waitFor();
}

for (const [variant, options] of Object.entries(SIZES)) {
  test.describe(variant, () => {
    test.use({ viewport: options.viewport, colorScheme: options.colorScheme });

    test('sign-in screen', async ({ page }) => {
      await page.goto(`/?${QUIET}#/`);
      await page.getByRole('heading', { name: 'Sign in to VitalStream' }).waitFor();
      if (variant === 'light') {
        await page.screenshot({ path: path.join(OUT, 'dashboard-signin.png') });
      }
    });

    test('ward overview', async ({ page }) => {
      await signIn(page, `/?${QUIET}#/`);
      await page.getByTestId('ward-grid').getByRole('link').nth(19).waitFor();
      await page.screenshot({ path: path.join(OUT, `dashboard-ward-${variant}.png`) });
    });

    test('patient detail', async ({ page }) => {
      await signIn(page, `/?${QUIET}#/patients/P-0003`);
      await page.locator('figure.chart path.series').nth(5).waitFor({ state: 'attached' });
      await page.screenshot({
        path: path.join(OUT, `dashboard-patient-${variant}.png`),
        fullPage: variant === 'mobile',
      });
    });

    test('alert feed', async ({ page }) => {
      await signIn(page, `/?${QUIET}#/alerts`);
      await page.getByTestId('alert-list').waitFor();
      await page.screenshot({ path: path.join(OUT, `dashboard-alerts-${variant}.png`) });
    });

    test('copilot mid-stream', async ({ page }) => {
      await signIn(page, `/?${QUIET}&tokenMs=140#/patients/P-0003`);
      await page.locator('figure.chart path.series').nth(5).waitFor({ state: 'attached' });
      await openCopilotMidStream(page);
      await page.screenshot({ path: path.join(OUT, `dashboard-copilot-${variant}.png`) });
    });
  });
}

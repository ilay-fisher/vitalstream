import { defineConfig, devices } from '@playwright/test';

// Port 25173 is inside the simulator/dashboard block (CONTRIBUTING 7); override with E2E_PORT.
const port = Number(process.env.E2E_PORT ?? 25173);

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list']],
  use: { baseURL: `http://localhost:${port}`, trace: 'retain-on-failure' },
  webServer: {
    command: `npx vite --mode mock --port ${port} --strictPort`,
    url: `http://localhost:${port}`,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
  projects: [
    {
      name: 'e2e',
      testMatch: /.*\.e2e\.ts/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'screenshots',
      testMatch: /.*\.shots\.ts/,
      workers: 1,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});

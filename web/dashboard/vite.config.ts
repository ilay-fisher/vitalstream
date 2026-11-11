import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

// The browser only talks to same-origin prefixes; the dev server (and nginx in the container)
// forwards them, which avoids CORS entirely. Both backends use /api/v1, hence the prefixes.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const strip = (prefix: string) => (path: string) => path.replace(new RegExp(`^${prefix}`), '');
  return {
    plugins: [react()],
    server: {
      port: Number(env.PORT ?? 5173),
      proxy: {
        '/clinical-api': {
          target: env.VITE_CLINICAL_API_URL || 'http://localhost:8082',
          changeOrigin: true,
          rewrite: strip('/clinical-api'),
        },
        '/copilot-api': {
          target: env.VITE_COPILOT_URL || 'http://localhost:8083',
          changeOrigin: true,
          rewrite: strip('/copilot-api'),
        },
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      coverage: {
        provider: 'v8',
        include: ['src/lib/**', 'src/hooks/**'],
        exclude: ['src/**/*.test.*', 'src/lib/types.ts'],
        reporter: ['text', 'html'],
        thresholds: { lines: 80, statements: 80, functions: 80, branches: 70 },
      },
    },
  };
});

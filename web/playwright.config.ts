import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/browser', fullyParallel: true, workers: 4, timeout: 30000,
  // Deterministic API fixtures must bypass workers. offline-production.mjs verifies the real worker separately.
  use: { baseURL: 'http://127.0.0.1:15173', channel: process.platform === 'win32' ? 'msedge' : undefined, headless: true, serviceWorkers: 'block', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 15173 --strictPort', url: 'http://127.0.0.1:15173', reuseExistingServer: true, timeout: 30000 },
  reporter: [['list']],
});

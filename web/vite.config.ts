import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath, URL } from 'node:url';
const proxyTarget = process.env.READER_API_PROXY || 'http://localhost:8080';
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { port: 5173, strictPort: true, proxy: { '/reader3': proxyTarget, '/assets': proxyTarget, '/epub': proxyTarget, '/health': proxyTarget } },
  build: { assetsDir: 'static', chunkSizeWarningLimit: 800 },
  test: { environment: 'jsdom', setupFiles: ['tests/setup.ts'], include: ['tests/**/*.test.{ts,tsx}'], restoreMocks: true },
});

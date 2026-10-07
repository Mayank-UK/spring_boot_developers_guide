import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// In Docker the server is reachable as the compose service name, not localhost;
// API_PROXY_TARGET lets compose override the proxy target without editing code.
const apiProxyTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '0.0.0.0',
    port: 5176,
    strictPort: true,
    proxy: {
      '/api': apiProxyTarget,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
});

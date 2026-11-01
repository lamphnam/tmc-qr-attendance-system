import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// `npm run dev` talks to the gateway of a running Compose stack (docker compose up -d).
// Override with GATEWAY_URL=http://localhost:9090 if you changed GATEWAY_HOST_PORT.
const gateway = process.env.GATEWAY_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': gateway,
      '/services': gateway,
    },
  },
  test: {
    environment: 'node',
  },
});

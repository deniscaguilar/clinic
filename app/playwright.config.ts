import { defineConfig } from '@playwright/test'

// Fluxo crítico contra o Compose local (T6.8). Pré-requisitos:
//   docker compose -f docker/compose.yaml --env-file docker/.env up -d
//   backend no perfil local: cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
// O app (Vite, com proxy de /api para o backend) sobe sozinho. `CLINIC_APP_URL` aponta outro endereço, se preciso.
const baseURL = process.env.CLINIC_APP_URL ?? 'http://localhost:5173'

export default defineConfig({
  testDir: 'e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [['list']],
  use: { baseURL, trace: 'retain-on-failure', locale: 'pt-BR' },
  webServer: process.env.CLINIC_APP_URL ? undefined : { command: 'npm run dev', url: baseURL, reuseExistingServer: true, timeout: 60_000 },
})

# App (PWA + backoffice)

React + TypeScript, uma base de código com duas áreas: app da clínica e backoffice em `/admin`. Tema por clínica via CSS variables a partir de `tenant_branding` (ADR-0007). Cliente da API gerado a partir do OpenAPI.

## Comandos

Use o Node 24 LTS (`source ~/.nvm/nvm.sh && nvm use`, lê `.nvmrc`).

| Comando | O que faz |
|---|---|
| `npm run dev` | Servidor de desenvolvimento (porta 5173; `/api` vai para `CLINIC_API_URL`, padrão `http://localhost:8080`) |
| `npm test` | Vitest (inclui o teste de contrato: o cliente versionado tem de bater com `backend/openapi/openapi.yaml`) |
| `npm run build` | Checagem de tipos, build e `scripts/check-pwa.mjs` (service worker só do shell, sem cache de API) |
| `npm run api:generate` | Regenera `src/api/schema.d.ts` a partir do contrato; rode após mudar o OpenAPI |
| `npm run audit` | `npm audit` com falha em vulnerabilidade Alta ou Crítica |
| `npm run e2e` | Playwright contra o Compose local: precisa do Compose no ar e do backend no perfil `local` (veja o topo de `playwright.config.ts`); o Vite sobe sozinho. Na primeira vez: `npx playwright install chromium` |

## PWA

O service worker guarda só o shell (HTML, JS, CSS, ícones). Não há cache de resposta da API e a navegação nunca captura `/api`. Os ícones são placeholders neutros gerados por `scripts/make-icons.mjs`; a identidade de cada clínica vem do branding.

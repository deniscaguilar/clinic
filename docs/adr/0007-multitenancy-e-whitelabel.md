# ADR-0007: Multi-tenancy e white-label

- **Status:** Aceita
- **Data:** 2026-10-26

## Decisão

- **Banco único, schema por módulo, `tenant_id` em toda tabela de negócio e Row-Level Security** do Postgres. O tenant é definido por transação (`SET LOCAL app.tenant_id`) a partir do usuário autenticado.
- **Teste de isolamento entre tenants obrigatório** para toda entidade nova.
- Autenticação no Cognito (OIDC); autorização (tenant e perfis) no banco da aplicação, indexada pelo `sub` do token. Evita Lambda de trigger e mantém tudo testável localmente.
- Perfis: `PLATFORM_ADMIN`, `CLINIC_ADMIN`, `DOCTOR`, `NURSE`, `MANAGER`, `STAFF`.
- **Branding por clínica** na tabela `tenant_branding` (nome, logo, favicon, cor primária e secundária; arquivos no S3). A API entrega no login e o front aplica por CSS variables (design tokens). Validar contraste de acessibilidade ao salvar as cores.
- O tenant é identificado pelo usuário logado. Subdomínio por clínica fica para depois (exige certificado wildcard).
- **Backoffice** na mesma base do front, em rotas `/admin`: criar clínicas, médicos e pacientes.

## Gatilho

Mentorado grande ou com exigência contratual pode receber banco dedicado; a arquitetura permite, mas não é feito agora.

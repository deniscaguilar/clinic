# Sistema da Clínica

Sistema de gestão clínica (PWA + backoffice) para o Instituto Pedro Guerra e, depois, para clínicas mentoradas da Endo Academy. Nome definitivo do produto: **a definir**.

## Estrutura

| Pasta | Conteúdo |
|---|---|
| `CLAUDE.md` | Regras obrigatórias para a IA e para o desenvolvimento |
| `docs/roadmap.md` | Roadmap por etapas |
| `docs/architecture.md` | Módulos, dependências permitidas e visão geral |
| `docs/workflow.md` | Fluxo SDD, Git, ambiente local e portão do CI |
| `docs/progress-log.md` | Diário de progresso: onde paramos e próximos passos |
| `docs/adr/` | Decisões de arquitetura (ADRs) |
| `specs/` | Specs (SDD): `spec.md` → `plan.md` → `tasks.md` por funcionalidade |
| `backend/` | Monolito modular Kotlin + Spring Boot (Maven) |
| `app/` | PWA e backoffice (React + TypeScript) |
| `infra/` | OpenTofu: módulos, stacks e ambientes |
| `docker/` | Ambiente local (Postgres, LocalStack, OIDC mock, Grafana LGTM) |

## Ambiente local

```bash
cp docker/env.example docker/.env
docker compose -f docker/compose.yaml --env-file docker/.env up -d
```

| Serviço | Endereço |
|---|---|
| Postgres | `localhost:5432` |
| LocalStack | `localhost:4566` |
| OIDC mock | `localhost:8081` |
| Grafana (LGTM) | `localhost:3000` |
| OTLP (entrada) | `localhost:4317` (gRPC), `localhost:4318` (HTTP) |

## Estado atual

M0 em andamento: spec, plano e tarefas aprovados; **S1 (esqueleto, banco, RLS, auditoria, segurança e observabilidade) concluída**. Próximo passo: S2 (clínicas, usuários, perfis e permissões). Ponto de retomada em `docs/progress-log.md`; acompanhamento das tarefas no board do Notion (`docs/notion-board.md`).

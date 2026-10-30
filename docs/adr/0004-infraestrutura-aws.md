# ADR-0004: Infraestrutura AWS de baixo custo

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Piloto com uma clínica, custo mínimo, mas com dado de gestão e (depois) dado clínico real. Desenvolvimento e validação feitos localmente.

## Decisão

| Item | Decisão |
|---|---|
| Região | **sa-east-1** (variável do OpenTofu) |
| Compute | **1 EC2 ARM (t4g.small)** com Docker Compose: `api` + `worker` no perfil `all` e Caddy para TLS; subir para t4g.medium se faltar memória |
| Banco | **RDS PostgreSQL** `db.t4g.micro`, single-AZ, gp3, backup automático com PITR, criptografia KMS, sub-rede privada, sem acesso público |
| Rede | VPC com EC2 em sub-rede pública (security group restrito); **sem NAT Gateway** (custo) |
| Arquivos | S3 criptografado |
| Segredos | SSM Parameter Store |
| Auth | Cognito (só autentica; perfis e tenant ficam no banco da aplicação) |
| IaC | **OpenTofu** em stacks com state em S3: `foundation`, `data`, `app`, `observability` |
| Deploy da aplicação | Pipeline separado da infra: CI constrói imagem arm64 e atualiza o serviço; GitHub Actions autentica por OIDC (sem chaves estáticas) |
| Registro de imagens | GHCR ou ECR (escolher pelo menor custo na hora) |
| Staging | Sem staging fixo: local + produção, e **ambiente de fumaça efêmero** na AWS antes de go-live e em releases arriscadas |

## Consequências

- Custo baixo, mas **sem alta disponibilidade** (EC2 e RDS single-AZ). **Gatilho:** Multi-AZ e/ou ECS Fargate com dado clínico real, segundo tenant ou SLA contratado.
- O Cognito é a parte mais difícil de migrar entre regiões; mitigado porque o banco da aplicação é a fonte de verdade de usuários e perfis.
- Chamadas a LLMs podem sair do Brasil mesmo com tudo em sa-east-1; tratar no `aigateway` (anonimização, contrato, base legal). Revisão jurídica antes da Etapa 4.
- Custos devem ser conferidos nas tabelas atuais da AWS antes do primeiro `apply`.

# Antes do go-live

Itens que ficam para quando o produto estiver pronto para sair do ambiente local. Nada aqui é feito sem a aprovação do Dênis, porque gera custo ou lida com dado real (CLAUDE.md, seções 8 e 10).

## Infraestrutura e homologação (AWS, gera custo)

- Stacks de OpenTofu em `infra/` (EC2 + Docker Compose, RDS, S3, SSM, sa-east-1) e `plan` revisado por humano antes de qualquer `apply`.
- Ambiente de fumaça efêmero: `apply`, testes, `destroy`.
- Restauração de snapshot do RDS testada.
- Verificação real do Cognito (hoje só o mock OIDC e o provedor de identidade falso).
- Deploy da imagem do backend (`backend/Dockerfile`, a mesma em todos os ambientes; só variáveis de ambiente mudam).

## Produto e conformidade

- Texto, base legal e retenção dos consentimentos revisados pelo jurídico (hoje só `docs/consent/textos-de-teste.md`, sem validade jurídica). Revisão jurídica de LGPD antes de entrar dado clínico real (Etapa 4).
- Provedor e custo do WhatsApp: trocar o adaptador falso (`clinic.notifications.whatsapp.provider=memory`) pelo real; produção não pode ficar no falso.
- Material de curadoria do Pedro e validação do M0 com a clínica piloto.

## Qualidade que já está no CI

Backend (`verify`, cobertura, ArchUnit, Modulith), app (testes e build), e2e (Playwright contra Postgres, LocalStack e OIDC mock), OSV-Scanner sobre o SBOM, `npm audit` e Trivy na imagem. O scan OWASP dependency-check (`./mvnw verify -Pscan`, com `NVD_API_KEY`) roda sob demanda.

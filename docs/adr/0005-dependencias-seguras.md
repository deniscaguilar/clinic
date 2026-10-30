# ADR-0005: Dependências atuais e seguras

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Exigência de usar sempre versões recentes e sem vulnerabilidades conhecidas. "Última versão" e "sem vulnerabilidade" nem sempre coincidem: a versão mais nova pode ter falha divulgada ontem, e scanners só conhecem falhas públicas. O que se garante é um **processo** que falha o build quando surge algo conhecido.

## Decisão

1. Versões vêm do BOM do Spring Boot; sobrescrever só para corrigir CVE, com comentário e link.
2. Apenas releases GA (sem milestone, RC ou snapshot); versões fixas.
3. Renovate ou Dependabot contínuo; patch com CI verde pode ser mesclado automaticamente.
4. CI bloqueante: OWASP dependency-check (chave gratuita da NVD) ou OSV-Scanner, e Trivy na imagem e no front. **Falha com severidade Alta ou Crítica.** Exceções em arquivo de supressão com justificativa e **data de expiração**. O scan de imagem vale para as imagens **que vão a produção** (aplicação e worker); imagens só de desenvolvimento e teste (LocalStack, OIDC mock, Grafana LGTM, Postgres local) ficam fora do bloqueio, mas são reescaneadas ao trocar de versão e o resultado é registrado (ver ADR-0012). Ajuste de 28/10/2026, aprovado por Denis no Plannotator.
5. Prazos de correção: crítica ≤ 48 h, alta ≤ 7 dias.
6. SBOM CycloneDX por build.
7. Imagem base com digest fixado e rebuild semanal.
8. Front: lockfile versionado, `npm audit` no CI, Node LTS.
9. Infra: providers com versão fixada, `checkov` e `tflint`.
10. `maven-enforcer-plugin`: JDK 25 e convergência de dependências. Secret scanning ativo no GitHub.

## Consequências

Atualizações constantes dão trabalho pequeno e frequente em vez de uma migração grande; o CI pode bloquear entregas por CVE alheio ao nosso código.

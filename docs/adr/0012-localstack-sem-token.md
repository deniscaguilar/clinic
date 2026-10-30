# ADR-0012: LocalStack fixado na última versão Community sem token

- **Status:** Aceita (aprovada por Denis no Plannotator em 28/10/2026)
- **Data:** 2026-10-28
- **Origem:** tarefa T1.1 do M0 (`specs/platform/m0-foundation/tasks.md`)

## Contexto

O ambiente local e os testes de integração usam o LocalStack para S3, SES e SSM. Desde a versão 2026.03.0 (23/03/2026) a imagem exige um token de autenticação, e o plano gratuito é só para uso não comercial. O projeto é comercial. As versões anteriores (edição Community, Apache 2.0) continuam publicadas no Docker Hub.

## Decisão

Fixar `localstack/localstack:4.14.0` (26/02/2026) **por digest** em `docker/compose.yaml`, sem token. Uso restrito a ambiente local e testes; nenhum dado real passa por ele. Não atualizar para 2026.03 ou mais novo sem nova decisão.

## Alternativas

- **Plano pago do LocalStack** (cerca de US$ 39 por licença por mês, segundo fontes de terceiros, sem conferência na página oficial): custo e token para guardar sem necessidade no M0.
- **MiniStack (MIT) ou MinIO + servidor SMTP de teste**: sem token, mas trocam o emulador e mudam os testes das tarefas T3.3 e T5.8.

## Consequências

- A imagem fica congelada e não recebe correções. O scan de imagem (Trivy) pode reprovar; nesse caso, usar o arquivo de supressão com justificativa e data de expiração (CLAUDE.md, seção 7).
- Rodar o Trivy nessa imagem já na T1.2.
- Cognito continua fora do LocalStack gratuito (plano do M0, seção 9.1).

## Gatilhos para reavaliar

Scan com vulnerabilidade que não possa ser suprimida, incompatibilidade com o SDK da AWS usado, ou necessidade de serviço ausente na 4.14.0.

# ADR-0011: Fila de notificações em tabela com worker

- **Status:** Aceita
- **Data:** 2026-10-28
- **Origem:** plano do M0 (`specs/platform/m0-foundation/plan.md`, seção 9.2, item 6)

## Contexto

O módulo `notifications` precisa enviar e-mail (e depois WhatsApp e SMS) com retentativa, sem perder mensagem e sem enviar a quem não consentiu. O CLAUDE.md proíbe SQS, Kafka, Redis e novos serviços sem aprovação.

## Decisão

Fila em **tabela do próprio Postgres** (`notifications.notification`) consumida por um **worker** no mesmo artefato (perfil `worker`; no piloto, `all`).

- `notifications` reage a eventos do `registry` (`ConsentAccepted`, `ConsentRevoked`) e grava a intenção de envio. Eventos são persistidos pelo registro do Spring Modulith (JDBC), que faz o papel de outbox.
- A tabela guarda tenant, `patient_id`, canal, tipo (modelo), status (`PENDING`, `SENT`, `FAILED`, `BLOCKED_NO_CONSENT`, `CANCELLED`), tentativas, `next_attempt_at` e `last_error_code`. **Sem destinatário nem texto**: o worker resolve o contato e monta a mensagem no envio, via `api` do `registry`.
- O worker pega lote com `FOR UPDATE SKIP LOCKED`, checa o consentimento antes de enviar (sem ele, `BLOCKED_NO_CONSENT`) e, na falha, aplica backoff exponencial até o máximo de tentativas, depois `FAILED`.
- Envio atrás do port `EmailSender`, com adaptador real (SES) e local.

## Alternativas

- **SQS:** serviço novo e custo; descartado por regra. Reavaliar se o volume ou a latência exigirem.
- **Envio síncrono no listener:** perde mensagem em falha e prende a transação.

## Consequências

- Sem infraestrutura nova; testável localmente com Testcontainers.
- O Postgres vira a fila: monitorar tamanho e idade da tabela; limpar mensagens antigas.
- Troca futura por SQS fica isolada atrás do caso de uso `DispatchPending`.

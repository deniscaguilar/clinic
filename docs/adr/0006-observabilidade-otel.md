# ADR-0006: Observabilidade com OpenTelemetry

- **Status:** Aceita
- **Data:** 2026-10-26
- **Substitui:** a proposta anterior de SigNoz (removido do plano)

## Decisão

A aplicação instrumenta com o **agente Java do OpenTelemetry** e fala apenas **OTLP** (`OTEL_EXPORTER_OTLP_ENDPOINT`). O destino é configuração do coletor, não da aplicação.

| Ambiente | Destino |
|---|---|
| Local | Container `grafana/otel-lgtm` (Grafana, Tempo, Loki, Prometheus), que já recebe OTLP |
| Produção | OpenTelemetry Collector em container ao lado da aplicação, exportando para CloudWatch/X-Ray (ADOT) |

Complementos em produção: `health` do Actuator, um uptime check gratuito e alarmes básicos.

## Regras

- Logs em JSON com `trace_id`, `tenant_id` e `user_id`.
- **Nunca** dado de paciente em log, trace ou métrica.
- Trocar de backend no futuro é editar o arquivo do coletor.

## Pendências

Escrever a configuração do coletor de produção ao criar a stack `observability`; conferir custos de CloudWatch/X-Ray em sa-east-1.

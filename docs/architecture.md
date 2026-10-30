# Arquitetura

Visão geral do Sistema da Clínica. As decisões que a sustentam estão em `adr/`. As regras operacionais estão em `../CLAUDE.md`.

## 1. Visão geral

Monolito modular em Kotlin + Spring Boot, com Clean Architecture dentro de cada módulo. Um artefato, perfis de execução `api` e `worker` (no piloto, `all`). Um PostgreSQL com um schema por módulo e isolamento de clínicas (tenants) por Row-Level Security.

```
 PWA + backoffice (React)  ──HTTPS──►  Caddy  ──►  backend (api + worker)  ──►  RDS PostgreSQL
                                                         │   │   │
                                                         │   │   └──► S3 (arquivos)
                                                         │   └──────► Cognito (OIDC)
                                                         └──────────► OTLP ──► coletor ──► CloudWatch / X-Ray
```

Local: o mesmo desenho com Postgres, LocalStack, OIDC mock e Grafana LGTM no Docker Compose (`../docker/compose.yaml`).

## 2. Módulos

| Módulo | Responsabilidade | Etapa do roadmap |
|---|---|---|
| `platform` | Identidade, clínicas (tenants), usuários, perfis, branding, auditoria, backoffice | 1 (M0) |
| `registry` | Profissionais, pacientes, procedimentos e consentimentos | 1 (M0) |
| `scheduling` | Agenda do médico por clínica | 2 (M9) |
| `inventory` | Estoque: cadastro de itens, entradas e saídas, ponto de reposição, alertas, consumo por procedimento e entrega ao paciente com termo de recebimento | 2 (M4) |
| `finance` | Financeiro | 2 (M4) |
| `reports` | Relatórios diários | 2 (M5) |
| `dashboard` | Painel do empresário | 3 (M3) |
| `notifications` | E-mail e WhatsApp (M0, versão mínima); SMS e push depois | 1 (M0), 2 e 3 |
| `endoai` | Integração com o Endo Academy AI | 4 (M1) |
| `patient360` | Visão e histórico do paciente | 4 (M2) |
| `aigateway` | Acesso único a LLMs | 4 em diante |
| `simulator`, `assistant` | Decisão de investimento e assistente do gestor | 6 (M6, M7) |

## 3. Dependências permitidas

Uma seta `A → B` significa que `A` pode usar a `api` pública de `B`. Qualquer outra dependência é proibida e quebra o build (ArchUnit e `Modulith.verify()`). **Não pode haver ciclos.**

| Módulo | Pode depender de |
|---|---|
| `platform` | (nenhum) |
| `registry` | `platform` |
| `notifications` | `platform`, `registry` |
| `scheduling` | `platform`, `registry` |
| `aigateway` | `platform` |
| `inventory` | `platform`, `registry`, `finance`, `notifications` |
| `finance` | `platform`, `registry` |
| `reports` | `platform`, `registry` |
| `endoai` | `platform`, `registry`, `aigateway` |
| `dashboard` | `platform`, `inventory`, `finance`, `reports`, `scheduling` |
| `patient360` | `platform`, `registry`, `endoai`, `aigateway` |
| `simulator` | `platform`, `finance`, `aigateway` |
| `assistant` | `platform`, `dashboard`, `patient360`, `aigateway` |

Regras de comunicação
- **Consulta síncrona:** pela `api` do módulo dono.
- **Reação a mudanças:** eventos de domínio publicados pelo módulo dono (registro de eventos do Spring Modulith persistido em JDBC, que funciona como outbox). Quem consome não conhece quem publica.
- `dashboard` consome eventos e mantém projeções de leitura no próprio schema; não faz join com tabelas de outros módulos.
- Módulos de base (`platform`, `registry`) não conhecem seus consumidores.

### Regra de negócio: venda, reserva e baixa de estoque

Exemplo: o paciente fecha um procedimento que usa 2 injeções. O financeiro define o valor, o estoque reserva 2 unidades e, em cada entrega, o paciente assina o termo de recebimento e a assinatura dá a baixa de 1 unidade.

**Fluxo**
1. `finance` registra a venda do procedimento ao paciente e publica o evento `ProcedureSold`.
2. `inventory` reage ao evento e **reserva** os itens pela receita de consumo (procedimento → itens e quantidades). A reserva é por item; o lote só é escolhido na entrega.
3. Se o saldo disponível (`em estoque − reservado`) não cobre a reserva, ou se cai abaixo do **limite parametrizável** do item, o `inventory` pede ao `notifications` que avise os responsáveis pela compra.
4. Na entrega, o profissional de enfermagem escolhe o lote e o paciente assina o termo de recebimento com um toque na tela. **Só então** o estoque dá a baixa (e a reserva daquela unidade é consumida). O paciente recebe a confirmação por e-mail, SMS ou WhatsApp.
5. Venda cancelada (`SaleCancelled`) **antes da entrega** libera a reserva. Unidades já entregues ao paciente **não voltam ao estoque**: se o paciente devolver, a unidade é descartada (ver abaixo).

**Donos dos dados**
- `registry`: paciente e procedimento.
- `finance`: venda e valor.
- `inventory`: itens, lotes e validade, reservas, receita de consumo, entrega e termo de recebimento. A receita e a reserva guardam só ids (`procedure_id`, `sale_id`), sem chave estrangeira entre schemas.

**Lote e validade**
- A seleção de lote segue **FEFO** (primeiro a vencer, primeiro a sair): a lista mostra os lotes por validade para a enfermagem saber qual priorizar. Lote vencido não pode ser escolhido.
- Lote ou produto perto do vencimento gera aviso ao responsável pelo estoque (prazo parametrizável).
- O termo registra o lote entregue, para rastrear em caso de recolhimento.

**Descarte**
- O `inventory` tem uma área de **descarte de produtos**, usada para produto **vencido** e para unidade **devolvida pelo paciente** depois da entrega.
- Quem descarta escolhe o produto e o lote, informa a quantidade, escreve o **motivo** (obrigatório) e salva. O registro guarda usuário, data e hora, e vai para a auditoria.
- Descarte de vencido **baixa o saldo** do lote. Descarte de devolução **não altera o saldo**, porque a unidade já saiu do estoque na assinatura do termo; ele só registra o fim da unidade, ligado ao termo e ao lote entregue.
- Descarte é imutável e exige permissão específica.

**Termo de recebimento**
- A assinatura e a baixa acontecem **na mesma transação**, dentro do `inventory`; não existe baixa sem termo assinado. Sem saldo, a entrega é recusada.
- O termo guarda lote, quantidade, paciente, profissional que entregou, instante, dispositivo e a imagem da assinatura com seu hash.
- Termo assinado é imutável. Só um **perfil com a permissão específica** cancela, **com justificativa**; o cancelamento é auditado (quem, quando, motivo), estorna a baixa e dispara nova notificação.
- Assinatura e entrega são dado de paciente: auditadas, fora de logs e traces, com RLS.

### Auditoria

Toda ação relevante no sistema gera um registro com **data e hora, usuário, ação, entidade afetada e resultado**, além de clínica e origem (canal e IP).

- A auditoria é do módulo `platform`. Os outros módulos gravam pela `api` dele (port `AuditTrail`), nunca direto na tabela.
- A trilha é **append-only**: o usuário da aplicação não tem permissão de `UPDATE` nem `DELETE` na tabela.
- O registro guarda ids e nomes de campos alterados, **sem copiar dado de paciente** para o texto da auditoria.
- Instantes em UTC no banco e exibidos em `America/Sao_Paulo`.
- Consulta da trilha restrita a perfis autorizados e filtrada por clínica (RLS).

## 4. Estrutura interna de cada módulo

```
com.endoacademy.clinic.<module>/
  api/          fachada pública e eventos
  domain/       entidades, value objects, regras (sem frameworks)
  application/  casos de uso e ports
  adapter/in/   web, listeners de evento
  adapter/out/  persistência e integrações externas
  config/
```

Dependência: `domain` ← `application` ← `adapter`. Detalhes em ADR-0002.

## 5. Multi-tenancy

Banco único, `tenant_id` em toda tabela de negócio e RLS (`SET LOCAL app.tenant_id` por transação). Autorização no banco da aplicação, indexada pelo `sub` do token. Detalhes em ADR-0007.

## 6. Canais

| Canal | Onde vive | Perfis |
|---|---|---|
| App da clínica (PWA) | `app/`, área principal | `CLINIC_ADMIN`, `DOCTOR`, `MANAGER`, `STAFF` |
| Backoffice (`/admin`) | `app/`, área administrativa; backend em `platform.adapter.in.web` | `PLATFORM_ADMIN` |

## 7. Pontos de extração futura

`endoai` e `assistant` são os candidatos naturais a virar serviço próprio (carga assíncrona, integração externa, possível tecnologia distinta). Os schemas separados e as `api`s já permitem isso. Critérios em ADR-0001.

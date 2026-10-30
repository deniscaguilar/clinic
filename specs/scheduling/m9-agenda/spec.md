# Spec: M9 · Agenda do médico (e da enfermagem)

- **Módulo:** `scheduling` (com mudanças pequenas em `registry`, `notifications` e `platform`)
- **Status:** Aprovada
- **Roadmap:** Etapa 2, módulo M9
- **Responsável:** Dênis Casado Aguilar

## 1. Problema e objetivo

A clínica marca consultas e procedimentos fora do sistema. Sem uma agenda no sistema, não há como saber quem atende quando, a recepção marca em cima de outro horário, o paciente não é lembrado e os módulos seguintes (venda de procedimento, estoque, relatório diário, painel) não têm o dado de **atendimentos marcados e realizados**. O objetivo é uma agenda **por profissional e por clínica** (médicos e enfermeiros), que respeita o horário de trabalho cadastrado no M0, impede conflitos e avisa o paciente.

## 2. Usuários e perfis afetados

| Quem | Faz |
|---|---|
| Recepção e equipe (`STAFF`) | Marca, remarca, cancela, conclui e marca falta; vê a agenda de todos os profissionais |
| Médico e enfermeiro (`DOCTOR`, `NURSE`) | Vê a **própria** agenda e atua nos próprios atendimentos |
| Gestão (`MANAGER`) | Vê a agenda de todos |
| `CLINIC_ADMIN` | Tudo acima e os parâmetros da agenda |
| Paciente | Recebe confirmação e lembretes; não acessa o sistema (portal é o M8) |

**Permissões novas** (fixas no código, perfis por clínica como no M0):

- `VIEW_SCHEDULE`: vê a agenda de **todos** os profissionais da clínica.
- `VIEW_OWN_SCHEDULE`: vê só a agenda do profissional **vinculado ao seu usuário** (o vínculo do M0, `practitioner.user_id`).
- `MANAGE_APPOINTMENTS`: marca, remarca, cancela, conclui e marca falta. Sem `VIEW_SCHEDULE`, só vale para os atendimentos do próprio profissional vinculado.
- `MANAGE_SCHEDULE_SETTINGS`: edita os parâmetros da agenda da clínica.

Sugestão para os perfis iniciais (cada clínica ajusta): `CLINIC_ADMIN` todas; `DOCTOR` e `NURSE` `VIEW_OWN_SCHEDULE` + `MANAGE_APPOINTMENTS`; `MANAGER` `VIEW_SCHEDULE`; `STAFF` `VIEW_SCHEDULE` + `MANAGE_APPOINTMENTS`.

## 3. Requisitos funcionais

- **RF1 · Atendimento:** um atendimento (`appointment`) tem **um profissional, um paciente, início, fim e situação**, mais **zero ou mais procedimentos** do `registry`. Sem procedimento é uma **consulta**; com procedimento(s), um **procedimento marcado**. Tudo da mesma clínica.
- **RF2 · Horário de trabalho:** o atendimento tem de caber **inteiro dentro de um intervalo** do horário de trabalho do profissional naquele dia da semana, no fuso `America/Sao_Paulo`, e **não atravessa a meia-noite**. Fora do expediente, recusa com erro claro.
- **RF3 · Conflito:** dois atendimentos **Marcados** do mesmo profissional não se sobrepõem (fim de um igual ao início do outro não é sobreposição). A regra vale também sob concorrência (duas pessoas marcando ao mesmo tempo): só uma consegue. Cancelado, Concluído e Faltou não ocupam horário para efeito de conflito.
- **RF4 · Duração:** cada **procedimento** tem uma **duração padrão em minutos** (campo novo no cadastro do `registry`). A duração sugerida de um atendimento é a **soma** dos padrões dos procedimentos; sem procedimento, vale a **duração padrão da consulta** da clínica (RF10). Quem marca pode **ajustar o fim**; o que fica gravado é início e fim.
- **RF5 · Situações:** `Marcado` (inicial), `Concluído`, `Cancelado` e `Faltou`.
  - `Marcado` → `Concluído` ou `Faltou`: só a partir do horário de início.
  - `Marcado` → `Cancelado`: a qualquer momento, com **motivo obrigatório**.
  - `Concluído`, `Cancelado` e `Faltou` são finais (não voltam a `Marcado`, não se remarcam).
- **RF6 · Remarcar:** só atendimento `Marcado`. Muda profissional, horário e/ou procedimentos no **mesmo** atendimento, com as mesmas regras de RF2 a RF4, e fica auditado (antes e depois, só campos e ids).
- **RF7 · Horários livres:** dado um profissional, um dia e uma duração, a API devolve os horários **livres** (dentro do expediente e sem conflito), para a equipe escolher sem adivinhar.
- **RF8 · Consultar a agenda:** listar os atendimentos de um profissional num período (dia ou semana), ordenados por início, com paginação limitada. `VIEW_SCHEDULE` consulta qualquer profissional; `VIEW_OWN_SCHEDULE`, só o vinculado ao próprio usuário (os outros dão 403). Cada item traz horário, situação, nome do paciente e nomes dos procedimentos (nada além disso na lista).
- **RF9 · Quem pode agir:** `MANAGE_APPOINTMENTS` para RF1, RF5 e RF6; sem `VIEW_SCHEDULE`, apenas sobre atendimentos do profissional vinculado ao próprio usuário.
- **RF10 · Parâmetros da clínica:** `CLINIC_ADMIN` (permissão `MANAGE_SCHEDULE_SETTINGS`) define a **duração padrão da consulta** (padrão 30 min), e as **horas antes** do **primeiro** (padrão 24 h) e do **segundo** lembrete (padrão 2 h). Validação: duração entre 5 e 480 min; lembretes entre 1 e 168 h, o primeiro maior que o segundo.
- **RF11 · Procedimento com duração:** o cadastro de procedimentos (M0) ganha a duração padrão em minutos (1 a 480; os existentes ficam com 30), na API e na tela.
- **RF12 · Avisos ao paciente:** ao **marcar**, o paciente recebe uma **confirmação**; antes do horário, **dois lembretes** (RF10). Por e-mail e WhatsApp, nos canais cadastrados, **só com consentimento de mensagens vigente** (regra do M0). Mensagem com o mínimo de dado pessoal: nome da clínica, data e hora e nome do profissional.
  - Remarcar **cancela os avisos pendentes e recria** confirmação (mensagem de novo horário) e lembretes; cancelar o atendimento **cancela** os pendentes e avisa o cancelamento.
  - Lembrete cujo horário já passou na hora de marcar não é criado. No envio, se o atendimento não estiver mais `Marcado`, nada é enviado.
  - Falha de envio nunca desfaz o atendimento; o envio é repetido pela fila (RF13 do M0).
  - Paciente sem consentimento vigente: nada é enviado e a tentativa é registrada (CA17 do M0).
- **RF13 · Auditoria:** marcar, remarcar, cancelar, concluir, marcar falta, mudar parâmetros e **ler a agenda** (um evento por consulta, com a contagem) são auditados pelo `AuditTrail`, sem dado de paciente no texto.
- **RF14 · Telas:** (a) agenda por dia e por semana com seletor de profissional; (b) "minha agenda" para quem só tem `VIEW_OWN_SCHEDULE`; (c) formulário de marcação (busca de paciente, profissional, procedimentos, data e horários livres); (d) ações cancelar (com motivo), concluir e faltou; (e) parâmetros da agenda; (f) duração padrão no cadastro de procedimentos. Textos em pt-BR por i18n; navegação por permissão.
- **RF15 · Isolamento de tenant:** toda leitura e escrita é restrita à clínica do usuário, garantida no banco (RLS), como no M0.

## 4. Critérios de aceite

- **CA1.** Given um profissional com expediente segunda das 08:00 às 12:00 When a recepção marca um atendimento de 09:00 a 09:30 numa segunda Then o atendimento fica `Marcado`.
- **CA2.** Given o mesmo profissional When tentam marcar de 11:45 a 12:15, ou numa segunda das 13:00 às 13:30, ou num domingo Then recebem erro de horário fora do expediente e nada é gravado.
- **CA3.** Given um atendimento `Marcado` de 09:00 a 09:30 When tentam marcar o mesmo profissional de 09:15 a 09:45 Then recebem erro de conflito e nada é gravado.
- **CA4.** Given um atendimento de 09:00 a 09:30 When marcam o mesmo profissional de 09:30 a 10:00 Then é aceito (fim igual ao início não conflita).
- **CA5.** Given duas requisições simultâneas para o mesmo profissional e horário When ambas são processadas Then exatamente uma é aceita e a outra recebe erro de conflito.
- **CA6.** Given um atendimento `Cancelado` ou `Faltou` de 09:00 a 09:30 When marcam outro atendimento nesse horário para o mesmo profissional Then é aceito.
- **CA7.** Given um procedimento de 20 min e outro de 40 min When marcam os dois no mesmo atendimento sem informar o fim Then o fim sugerido é início + 60 min; e When informam um fim diferente Then vale o informado.
- **CA8.** Given uma consulta sem procedimento e a duração padrão da clínica em 45 min When marcam sem informar o fim Then o fim sugerido é início + 45 min.
- **CA9.** Given um atendimento `Marcado` com início no futuro When tentam marcá-lo `Concluído` ou `Faltou` Then recebem erro; depois do início, é aceito.
- **CA10.** Given um atendimento `Marcado` When cancelam sem motivo Then recebem erro de validação; com motivo, fica `Cancelado`, auditado.
- **CA11.** Given um atendimento `Concluído`, `Cancelado` ou `Faltou` When tentam remarcar, cancelar ou mudar a situação Then recebem erro e nada muda.
- **CA12.** Given um atendimento `Marcado` When a recepção o remarca para outro horário livre do mesmo profissional Then o horário antigo fica livre, o novo ocupado e a mudança auditada; para um horário em conflito ou fora do expediente, recebe erro e nada muda.
- **CA13.** Given um profissional, um dia e uma duração de 30 min When a equipe pede os horários livres Then recebe apenas horários dentro do expediente que não conflitam com atendimentos `Marcado`.
- **CA14.** Given um usuário com `VIEW_OWN_SCHEDULE` vinculado ao profissional A When consulta a agenda do profissional B Then recebe 403; a do A, consulta normalmente.
- **CA15.** Given um usuário com `VIEW_SCHEDULE` When consulta a agenda de qualquer profissional Then vê os atendimentos; sem nenhuma das duas permissões, 403.
- **CA16.** Given um usuário com `MANAGE_APPOINTMENTS` e `VIEW_OWN_SCHEDULE`, sem `VIEW_SCHEDULE`, vinculado ao profissional A When tenta marcar ou cancelar atendimento do profissional B Then recebe 403.
- **CA17.** Given um profissional em duas clínicas com expedientes diferentes When marcam na clínica A Then vale só o expediente e a agenda da clínica A.
- **CA18.** Given um paciente, profissional ou procedimento de outra clínica When tentam usá-lo num atendimento Then recebem 404 e nada é gravado (e, direto no banco, a RLS impede qualquer acesso entre clínicas).
- **CA19.** Given um atendimento marcado para um paciente com consentimento de mensagens vigente e e-mail e WhatsApp When o atendimento é gravado Then a confirmação é enviada por cada canal cadastrado e os dois lembretes ficam programados nos horários definidos pela clínica (verificado com os adaptadores locais).
- **CA20.** Given um atendimento com lembretes pendentes When é remarcado Then os pendentes são cancelados e recriados para o novo horário, com aviso de novo horário; When é cancelado Then os pendentes são cancelados e o paciente é avisado do cancelamento.
- **CA21.** Given um atendimento em menos de 24 h When é marcado Then o lembrete de 24 h não é criado e o de 2 h, se ainda no futuro, é criado.
- **CA22.** Given um paciente que revogou o consentimento de mensagens When o atendimento é marcado, remarcado ou o lembrete vence Then nada é enviado e a tentativa é registrada; o atendimento permanece.
- **CA23.** Given uma falha no envio da confirmação When o atendimento é salvo Then o atendimento permanece e o envio é repetido depois.
- **CA24.** Given um lembrete programado When chega a hora de enviá-lo e o atendimento já não está `Marcado` Then nada é enviado.
- **CA25.** Given um `CLINIC_ADMIN` When altera a duração padrão da consulta ou os horários dos lembretes com valores inválidos (lembrete 1 menor ou igual ao 2, duração fora de 5 a 480) Then recebe erro de validação e nada muda; com valores válidos, passam a valer para os novos atendimentos.
- **CA26.** Given o cadastro de procedimentos When a equipe informa duração fora de 1 a 480 Then recebe erro; procedimentos já existentes mostram 30 min.
- **CA27.** Given qualquer marcação, remarcação, cancelamento, conclusão, falta, mudança de parâmetros ou consulta da agenda When ocorre Then existe um evento de auditoria (data e hora, usuário, ação, entidade e resultado), sem dado de paciente no texto, e a aplicação não consegue alterá-lo.
- **CA28.** Given o front When a equipe usa as telas Then os textos estão em pt-BR, a navegação respeita as permissões e os erros aparecem pelo `code` da API.
- **CA29.** Given as tabelas novas de `scheduling` When a suíte roda Then existe teste de isolamento de tenant para cada uma (CA10 do M0).
- **CA30.** Given o ambiente local When `./mvnw verify` e o fluxo crítico do Playwright rodam Then passam, e o fluxo inclui marcar um atendimento.

## 5. Requisitos não funcionais

- **LGPD e segurança:** nenhum dado de paciente em logs, traces ou métricas. A lista da agenda mostra apenas nome do paciente; nada de CPF ou contato. Mensagens ao paciente levam o mínimo (clínica, data, hora, profissional) e dependem de consentimento vigente. O paciente **nunca** vai em URL.
- **Multi-tenant:** `tenant_id` e RLS em toda tabela nova, com teste de isolamento.
- **Consistência:** o conflito é garantido pelo banco (restrição de exclusão sobre profissional e intervalo, só para `Marcado`), não só pelo código; fallback aceito: bloqueio consultivo por profissional na transação.
- **Tempo e fuso:** instantes em `timestamptz` (UTC no banco); expediente e exibição em `America/Sao_Paulo`; nunca `LocalDateTime` sem fuso para instantes.
- **Arquitetura:** Clean Architecture; acesso a outros módulos só pela `api`; `Clock` injetado; **sem FK entre schemas**. A agenda guarda apenas ids de paciente, profissional e procedimento.
- **Observabilidade:** traces e métricas via OTLP com `tenant_id` e `user_id`.
- **Desempenho:** sem meta numérica no piloto; listagens paginadas e limitadas a um período (máximo de 31 dias por consulta).
- **Acessibilidade:** telas navegáveis por teclado e com rótulos.

## 6. Fora de escopo

- Atendimentos recorrentes, salas e recursos, lista de espera e encaixes especiais além do bloqueio de RF2 e RF3.
- Mais de um profissional no mesmo atendimento.
- Agendamento pelo próprio paciente e confirmação por resposta do paciente (portal M8).
- Sincronização com calendários externos (Google, Apple).
- SMS e push (entram com o M4); o M9 usa e-mail e WhatsApp.
- Venda de procedimento, reserva de estoque e faturamento ao concluir (M4); o M9 só publica o evento de conclusão para eles consumirem.
- Prontuário e ficha do paciente (Etapa 4); identidade visual e design (etapa final de construção).
- Provedor real de WhatsApp (continua o adaptador falso; decisão pendente).

## 7. Dados envolvidos

- **Novos (`scheduling`):** atendimento (profissional, paciente, início, fim, situação, motivo do cancelamento, autor), procedimentos do atendimento (ids) e parâmetros da clínica (duração padrão da consulta, horas dos dois lembretes). Dado pessoal **por referência**: o atendimento guarda só o id do paciente; o nome vem do `registry` na leitura. É dado pessoal de saúde indireto (quem foi atendido e quando): auditar leitura e escrita, sem retenção definida ainda (decisão futura com o jurídico, junto com a do consentimento).
- **Alterados:** procedimento (duração padrão, `registry`), catálogo de permissões (`platform`) e tipos de notificação (`notifications`: `APPOINTMENT_CONFIRMATION`, `APPOINTMENT_REMINDER`, aviso de remarcação e de cancelamento).
- **Eventos (`scheduling.api`, só ids):** `AppointmentScheduled`, `AppointmentRescheduled`, `AppointmentCancelled`, `AppointmentCompleted` e `AppointmentNoShow`. O `notifications` consome os três primeiros; os dois últimos ficam para o M4.

## 8. Decisões e perguntas em aberto

**Decididas por Dênis em 30/10/2026**
- M9 é a primeira spec da Etapa 2.
- Conflito de horário e horário fora do expediente: **bloqueados** (RF2, RF3).
- Duração padrão **no procedimento**, e **duração de consulta parametrizada por clínica** (RF4, RF10).
- Permissões: `VIEW_SCHEDULE`, `VIEW_OWN_SCHEDULE` e `MANAGE_APPOINTMENTS` (seção 2).
- O M9 **já avisa o paciente**: confirmação e **dois lembretes**, parametrizados por clínica, padrão 24 h e 2 h (RF12). Isto é a decisão específica que o CLAUDE.md (seção 10) exige para criar mensagem a paciente e novo tipo de item na fila de notificações.
- Situações: Marcado, Concluído, Cancelado, Faltou (RF5).
- Um atendimento pode ter **vários procedimentos** e a agenda vale para **enfermeiros** também (RF1).

**Premissas minhas, para você confirmar na revisão**
1. Valores iniciais: consulta 30 min, lembretes 24 h e 2 h, procedimentos existentes 30 min.
2. Quem só tem `MANAGE_APPOINTMENTS` + `VIEW_OWN_SCHEDULE` age só nos atendimentos do próprio profissional (RF9).
3. Atendimento de profissional **inativo** ou com procedimento **inativo** não pode ser marcado; os já marcados continuam.
4. Mudar o horário de trabalho do profissional **não** mexe em atendimentos já marcados (a equipe remarca se precisar).
5. Remarcar mantém o mesmo atendimento (mesmo id) e gera aviso de novo horário.
6. Clínicas **existentes** não ganham as permissões novas nos perfis que já têm; o `CLINIC_ADMIN` as atribui (o plano trata o caso dos perfis iniciais de clínicas novas e a regularização das existentes).

**Em aberto (não bloqueiam a spec)**
- Texto exato das mensagens de confirmação, lembrete, remarcação e cancelamento (rascunho de teste no plano; texto final com o jurídico/clínica).
- Retenção dos dados da agenda (junto com a decisão de retenção do consentimento).

## 9. Aprovação

- [x] Aprovada por: Dênis, via Plannotator, em 30/10/2026

# Spec: M0 · Fundação (identidade, tenancy, branding e backoffice)

- **Módulos:** `platform` (identidade, clínicas, branding, auditoria, backoffice), `registry` (profissional, paciente, procedimento e consentimentos) e `notifications` (e-mail e WhatsApp, em versão mínima)
- **Status:** Aprovada
- **Roadmap:** Etapa 1, módulo M0
- **Responsável:** Dênis Casado Aguilar

## 1. Problema e objetivo

Antes de qualquer módulo de gestão ou clínico, precisamos de uma base segura e multi-clínica: quem entra, em qual clínica, com qual perfil, com qual identidade visual, e uma forma de cadastrar clínicas, médicos e pacientes. A fundação deve servir ao piloto (Clínica da DCA) e à fase 2 (mentorados) sem retrabalho.

## 2. Usuários e perfis

| Perfil | Pode |
|---|---|
| `PLATFORM_ADMIN` | Criar e configurar clínicas, criar o primeiro `CLINIC_ADMIN`, ver todas as clínicas |
| `CLINIC_ADMIN` | Gerir usuários, médicos, pacientes e branding da própria clínica |
| `DOCTOR` | Ver pacientes da própria clínica |
| `NURSE` | Enfermagem: entrega de produtos e consulta de lotes (permissões detalhadas no M4) |
| `MANAGER` | Perfil de gestão (permissões detalhadas definidas pelos módulos M3 a M5) |
| `STAFF` | Operação do dia a dia (permissões detalhadas depois) |

**Permissões e perfis por clínica.** As **permissões** são fixas no código (por exemplo, `CANCEL_RECEIPT_TERM`, `RECEIVE_STOCK_ALERTS`, `DISCARD_STOCK`). Cada clínica monta seus **perfis** como conjuntos de permissões; os perfis acima são os modelos iniciais. `PLATFORM_ADMIN` é fixo e não pertence a nenhuma clínica.

## 3. Requisitos funcionais

- **RF1 · Autenticação:** a API valida tokens OIDC (Cognito em produção, provedor mock local). Tokens inválidos ou expirados são rejeitados.
- **RF2 · Usuário e perfil:** o `sub` do token é mapeado, no banco da aplicação, a um ou mais **vínculos** com clínica (tenant), cada um com seus perfis. Quem tem mais de uma clínica escolhe a clínica ao entrar; a sessão vale para uma clínica por vez.
- **RF3 · Clínicas (backoffice):** `PLATFORM_ADMIN` cria, edita e desativa clínicas.
- **RF4 · Usuários e médicos:** `CLINIC_ADMIN` cadastra usuários e médicos da própria clínica; o convite cria a identidade no provedor via port `IdentityProvider`. Um médico que atua em duas clínicas é **cadastrado em cada uma**, com o mesmo login e **horário de trabalho próprio por clínica**. A agenda do médico não faz parte do M0.
- **RF4a · Perfis por clínica:** `CLINIC_ADMIN` cria e edita perfis como conjuntos de permissões e os atribui a usuários da própria clínica.
- **RF5 · Pacientes (cadastro):** por clínica, com nome completo, CPF (validado pelos dígitos verificadores), data de nascimento, WhatsApp (celular), e-mail, telefone fixo (opcional) e endereço completo (CEP, logradouro, número, complemento opcional, bairro, cidade, UF). São **obrigatórios**: nome completo, CPF, data de nascimento e endereço, e **pelo menos um contato: WhatsApp (celular) ou e-mail**. O CPF é único por clínica. Os dados também servem à futura emissão de nota fiscal. Sem dado clínico nesta etapa.
- **RF5a · Consentimento no cadastro:** o cadastro do paciente exige o aceite do consentimento obrigatório (checkbox, marcado na clínica pela equipe). Sem ele, o paciente não é salvo.
- **RF11 · Tipos de consentimento:** cada clínica cadastra **tipos de consentimento** no backoffice, com nome, texto, indicação de obrigatório no cadastro ou não, e versão. Exemplos: cadastro e recebimento de mensagens; uso de imagem; medicamento específico que exige aprovação. Alterar o texto cria uma nova versão; aceites antigos continuam ligados à versão que o paciente viu.
- **RF12 · Aceite e revogação:** o aceite de cada tipo registra paciente, tipo e versão, data e hora, canal (na clínica) e usuário que registrou. O paciente pode revogar; a revogação também é registrada e auditada. Revogar o consentimento de mensagens interrompe os envios.
- **RF13 · Notificação do aceite:** a cada aceite, o paciente recebe a confirmação por **e-mail e WhatsApp**, nos canais que tiver cadastrados, com o mínimo de dado pessoal (nome do tipo e data). A falha de envio não desfaz o aceite: o envio é assíncrono e tentado de novo.
- **RF6 · Procedimentos e profissionais (cadastro mínimo):** entidades de base para os módulos seguintes.
- **RF7 · Branding por clínica:** nome, logo, favicon, cor primária e secundária; a API entrega no login e o front aplica por CSS variables.
- **RF8 · Isolamento de tenant:** toda leitura e escrita é restrita à clínica do usuário, garantida no banco (RLS).
- **RF9 · Auditoria:** registro de data e hora, usuário, ação, entidade e resultado de toda ação relevante (criar, alterar, consultar dado de paciente, login, mudança de perfil, cancelamentos). Os demais módulos gravam pela `api` do `platform`. A trilha é append-only.
- **RF10 · Shell do app:** PWA instalável com navegação por perfil e backoffice em `/admin`.

## 4. Critérios de aceite

- **CA1.** Given um token válido de usuário da clínica A When ele lista pacientes Then recebe apenas pacientes da clínica A.
- **CA2.** Given um usuário da clínica A When tenta ler ou alterar um paciente da clínica B (por ID) Then recebe 404 e nenhuma linha é afetada (verificado também direto no banco com RLS).
- **CA3.** Given um token ausente, inválido ou expirado When chama qualquer endpoint protegido Then recebe 401.
- **CA4.** Given um `DOCTOR` When tenta criar uma clínica Then recebe 403.
- **CA5.** Given um `PLATFORM_ADMIN` When cria uma clínica com o e-mail do primeiro administrador Then a clínica existe, o administrador é convidado e consegue entrar.
- **CA6.** Given a clínica A com cor primária verde e a clínica B com azul When cada uma abre o app Then o topo e os componentes usam a respectiva cor e o logo correto.
- **CA7.** Given uma cor primária com contraste insuficiente com o texto When o `CLINIC_ADMIN` tenta salvar Then recebe erro de validação explicando o contraste.
- **CA8.** Given qualquer leitura ou escrita de dado de paciente, ou qualquer ação relevante When ela ocorre Then existe um registro de auditoria com data e hora, usuário, ação, entidade e resultado, e a aplicação não consegue alterar nem apagar esse registro.
- **CA9.** Given o ambiente local When `docker compose up` e `./mvnw verify` são executados Then a aplicação sobe e todos os testes passam sem acesso à internet além de dependências.
- **CA10.** Given qualquer entidade de negócio criada nesta etapa When a suíte de testes roda Then existe teste de isolamento de tenant para ela.
- **CA11.** Given um médico cadastrado nas clínicas A e B com horários diferentes When ele entra e escolhe a clínica A Then vê apenas dados e o horário de trabalho da clínica A.
- **CA12.** Given um `CLINIC_ADMIN` que cria um perfil com um conjunto de permissões When atribui o perfil a um usuário Then o usuário só consegue executar as ações cobertas por essas permissões (as demais retornam 403).
- **CA13.** Given o cadastro de um paciente sem o consentimento obrigatório marcado, ou sem nome completo, CPF, data de nascimento ou endereço, ou sem nenhum contato (WhatsApp ou e-mail) When a equipe tenta salvar Then recebe erro de validação e nada é gravado.
- **CA14.** Given um CPF inválido, ou já cadastrado na mesma clínica When a equipe tenta salvar Then recebe erro de validação.
- **CA15.** Given um `CLINIC_ADMIN` que cria um tipo de consentimento e depois altera o texto When salva Then existe uma nova versão e os aceites anteriores continuam ligados à versão antiga.
- **CA16.** Given um paciente que aceita um consentimento When o aceite é salvo Then ficam registrados tipo, versão, data e hora e usuário, e a confirmação é enviada por cada canal cadastrado, e-mail e/ou WhatsApp (verificado com os adaptadores locais).
- **CA17.** Given um paciente que revogou o consentimento de mensagens When o sistema tenta enviar uma notificação a ele Then nada é enviado e a tentativa é registrada.
- **CA18.** Given falha no envio da confirmação When o aceite é salvo Then o aceite permanece e o envio é repetido depois.

## 5. Requisitos não funcionais

- Segurança e LGPD: nenhum dado de paciente em logs, traces ou métricas; criptografia em trânsito e em repouso.
- Observabilidade: traces e métricas via OTLP desde o primeiro endpoint, com `tenant_id` e `user_id` (nunca dados de paciente).
- Acessibilidade: validação de contraste de cores; PWA responsivo.
- Portabilidade: tudo atrás de ports (`IdentityProvider`, `FileStorage`, `Clock`) com adaptadores locais.
- Desempenho: sem meta numérica no piloto; paginação obrigatória em listagens.

## 6. Fora de escopo

- Dado clínico, prontuário, integração com o Endo AI (Etapa 4).
- Estoque, financeiro, relatórios e painel (Etapas 2 e 3). Vincular consentimento a medicamento ou procedimento específico (M4).
- Emissão de nota fiscal (futuro; o cadastro já guarda os dados).
- Provedor real de WhatsApp em produção: depende de decisão de custo e de conta Business. No M0 há adaptador local e e-mail.
- **Ficha histórica do paciente:** bioimpedância, medicamentos, anamnese (histórico familiar, medicamentos usados, problemas crônicos, dores), procedimentos realizados e em andamento, exames com comparativos e procedimentos fechados ou recusados por consulta. Vai para o M2 (Paciente 360), com o modelo definido em spec própria.
- **Agenda do médico** (consultas e horários marcados): módulo próprio `scheduling`, fase seguinte (roadmap M9). O M0 guarda só o horário de trabalho por clínica.
- **Login do paciente** (e-mail e senha, com Google e Apple): portal do paciente, M8.
- Subdomínio por clínica e certificados wildcard.
- Cobrança, planos e onboarding self-service (fase 2).
- Recuperação de senha personalizada (usa o fluxo padrão do provedor).

## 7. Dados envolvidos

Clínica, usuário, vínculo usuário-clínica, perfil e permissões, paciente (dado pessoal: nome, CPF, data de nascimento, e-mail, telefone, WhatsApp, endereço), consentimento, profissional com horário de trabalho, procedimento, branding (arquivos no S3), auditoria. **Dado pessoal de paciente** já aparece aqui; a retenção precisa de definição (ver perguntas).

## 8. Decisões e perguntas em aberto

**Decididas por Dênis em 26/10/2026**
- Campos do paciente: dados cadastrais, telefone e WhatsApp, endereço (RF5). A ficha histórica fica para o M2.
- Médico em mais de uma clínica: cadastrado em cada uma, com horário de trabalho próprio (RF2, RF4).
- Idioma: somente pt-BR no início.
- Nome do produto: a definir mais adiante. Fica provisório, sem espalhar o nome em configurações externas.
- Consentimento: checkbox no cadastro e, além disso, **tipos de consentimento** cadastráveis no backoffice (RF5a, RF11 a RF13).
- Perfis por clínica com permissões: sim (RF4a). Perfil de enfermagem: sim, já no M0.
- Campos obrigatórios do paciente: nome completo, CPF, data de nascimento, endereço, consentimento e **pelo menos um contato (WhatsApp ou e-mail)**.
- Tipos de consentimento: cadastrados pelo `CLINIC_ADMIN` de cada clínica; o `PLATFORM_ADMIN` pode oferecer modelos.
- Provedor e custo de WhatsApp e SMS: a definir (o M0 usa adaptadores locais).
- Agenda (M9): início da Etapa 2.
- Cadastro: no M0 a equipe da clínica cadastra o paciente e registra o consentimento. O autocadastro pelo paciente vem com o portal (M8).

- Agenda do médico: módulo próprio `scheduling`, na fase 1 (roadmap M9).
- Retenção dos dados e texto do consentimento: definidos depois (não bloqueiam o M0; precisam estar definidos antes de dado real).

**Em aberto**
(nenhuma pergunta bloqueia o M0; retenção e texto do consentimento e o provedor de WhatsApp/SMS estão registrados como decisões futuras.)

## 9. Aprovação

- [x] Aprovada por: Dênis, via Plannotator (Approve na revisão da pasta inteira), em 26/10/2026

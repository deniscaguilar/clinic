# Roadmap: Sistema da Clínica (Clínica da DCA → mentorados Endo Academy)

**Status:** rascunho v1, para validar com o DCA
**Base:** reunião de 21/10/2026 (ata, transcrição Gemini e notas/transcrição Notion)
**Autor:** Dênis Casado Aguilar

> Sem datas de calendário. As estimativas são de tamanho relativo (P / M / G) e só serão convertidas em prazo ao fim da Etapa 0, depois do material do DCA e da visita ao Fulano.

---

## 1. Objetivo

Um app de gestão clínica que dê ao DCA, na palma da mão, a visão do **empresário** (faturamento, metas, estoque, fluxo de caixa, relatórios) e do **médico** (histórico do paciente), com o Endo Academy AI integrado. Primeiro validado na Clínica da DCA (cerca de R$ 1 mi/mês), depois oferecido a mentorados que faturam a partir de R$ 100 mil/mês.

**Prova de valor do piloto:** o DCA conseguir dizer "isso mexeu meu ponteiro" com números (decisões de compra melhores, clínica acompanhada à distância, menos tempo procurando relatório). Isso é o que sustenta a venda na fase 2.

## 2. Princípios de sequenciamento

1. **Gestão antes de clínico.** É a dor mais repetida pelo DCA ("às cegas") e depende menos das incógnitas (API do Endo AI, prontuário atual).
2. **Dados antes de painel.** O painel (M3) consome o que M4 e M5 produzem; um painel sem fonte de dados fica vazio.
3. **Fundação enxuta, porém pronta para o futuro.** Multi-clínica e perfis desde o início; entidade mínima de paciente e procedimento para o clínico se encaixar depois.
4. **Clínico logo em seguida.** Gestão sozinha é commodity; o diferencial é Endo AI + Paciente 360 + IA.
5. **Podar.** Uma coisa de cada vez ("vamos podar, começa com uma coisa"), respeitando a capacidade de execução.

## 3. Visão geral

| Etapa | Nome | Módulos | Tamanho | Gate de saída |
|---|---|---|---|---|
| 0 | Descoberta | n/a | P | Escopo e arquitetura aprovados pelo DCA |
| 1 | Fundação | M0 | M | Login, perfis e base multi-clínica no ar |
| 2 | Dados de gestão | M9, M4, M5 | G | Estoque, financeiro e relatórios diários rodando no app |
| 3 | Painel do empresário | M3 | M | DCA acompanha a clínica pelo celular |
| 4 | Clínico | M1, M2 | G | Histórico do paciente e briefing pré-consulta |
| 5 | Piloto e medição | n/a | M | Resultados medidos em uso real |
| 6 | Inteligência | M6, M7, M8 | G | Simulador, assistente e evolução do paciente |
| 7 | Fase 2: mentorados | F2-a, F2-b | G | Primeiro mentorado implantado |

A etapa 5 (piloto) começa já ao fim da etapa 3, em paralelo com a 4: a clínica passa a usar a gestão enquanto o lado clínico é construído.

---

## 4. Detalhamento

### Etapa 0: Descoberta
**Objetivo:** transformar a curadoria do DCA em escopo fechado e decidir as incógnitas técnicas.

**Entradas:** material do DCA (WhatsApp, até 28/10): business plan, mapeamento de ideias, documento do clube de treinos e Nutri.

**Atividades**
- Reunião com Fulano de Tal: ver o sistema de estoque e financeiro, banco de dados, stack, regras de negócio.
- Demo do Endo Academy AI (com o Beltrano, se possível): API, formato dos dados, onde roda, quem opera.
- Mapear de onde vêm faturamento e metas hoje (agenda, ERP, planilha, sistema do Fulano).
- Identificar o prontuário eletrônico atual e se há API.
- Levantar relatórios diários da equipe: formato, campos, quem envia.
- Definir plataforma do app (nativo, PWA ou web responsivo) e nome do produto (as transcrições dizem "Self Clinic", a ata diz "Endo Academy").
- Esboço de requisitos de LGPD e sigilo médico.

**Decisões a tomar**
| Decisão | Opções |
|---|---|
| Base do Fulano | Reaproveitar, refatorar ou refazer |
| Origem do faturamento | Integração automática ou lançamento estruturado (M5) no início |
| Prontuário | Integrar com o existente (preferência do DCA) ou construir (fase 2) |
| Escopo treino e nutrição | Fase 1 ou posterior |
| Plataforma | PWA (mais rápido) ou nativo |

**Saída:** documento de escopo da fase 1, arquitetura, backlog priorizado e estimativas em prazo.

---

### Etapa 1: Fundação (M0)
**Objetivo:** base técnica enxuta, mas que não precise ser refeita para a fase 2.

- Autenticação e perfis: dono, médico, enfermagem, gestor, equipe (permissões fixas, perfis por clínica).
- **Multi-clínica desde o início** (isolamento de dados por clínica).
- Entidades mínimas: clínica, usuário, paciente (identificação básica), procedimento e profissional, para o clínico se encaixar depois sem refazer o modelo.
- Trilha de auditoria e logs.
- Estrutura do app (shell, navegação, tema) e ambiente de deploy.
- Requisitos base de LGPD: **tipos de consentimento** cadastráveis no backoffice (cadastro e mensagens, uso de imagem, medicamento específico), aceite versionado, revogação e confirmação ao paciente por e-mail e WhatsApp. Retenção definida depois. O peso maior de dado clínico entra na etapa 4.
- Cadastro do paciente com nome completo, CPF, data de nascimento e endereço obrigatórios, mais pelo menos um contato (WhatsApp ou e-mail). Os dados também servem à futura nota fiscal.
- Notificações em versão mínima (e-mail e WhatsApp).

**Entrega:** app abrindo com login e perfis, em ambiente de homologação.

**Situação (30/10/2026):** implementação do M0 (backend e app) concluída e testada no ambiente local; faltam o ambiente de homologação e a validação com o DCA.

---

### Etapa 2: Dados de gestão (M9, M4, M5)
**Objetivo:** alimentar o sistema com os dados que o painel vai consumir.

**M9 · Agenda do médico** (módulo `scheduling`)
- Agenda por médico e por clínica, respeitando o horário de trabalho cadastrado no M0.
- Consultas e procedimentos marcados, com paciente, profissional e procedimento do `registry`.
- Um médico em mais de uma clínica tem uma agenda por clínica.

**M4 · Estoque e financeiro**
- Estoque: cadastro de itens, entradas e saídas, ponto de reposição e alertas.
- Lote e validade: controle por lote em injeções e produtos em geral; a enfermagem vê os lotes por validade (primeiro a vencer, primeiro a sair) ao escolher o produto; aviso ao responsável pelo estoque quando um lote ou produto estiver perto do vencimento.
- Consumo por procedimento: cada procedimento define os itens e as quantidades que consome (por exemplo, 2 injeções).
- Reserva: a venda do procedimento ao paciente reserva os itens no estoque. Se faltar saldo, ou se o saldo chegar ao limite parametrizável, os responsáveis pela compra são notificados.
- Entrega com termo de recebimento: a cada entrega (por exemplo, a primeira injeção), o paciente assina com um toque na tela, **a assinatura dá a baixa do estoque** e o paciente recebe confirmação por e-mail, SMS ou WhatsApp. Sem termo assinado, não há baixa.
- Descarte: área para descartar produto vencido ou devolvido pelo paciente (a unidade devolvida nunca volta ao estoque). Escolhe produto e lote, informa quantidade e motivo, salva; auditado.
- Cancelamento de assinatura (engano): só perfil com permissão específica, com justificativa, auditado (usuário, data e hora).
- Canais de notificação completos (SMS e push, além de e-mail e WhatsApp do M0) entram no M4, porque o estoque e o termo dependem deles.
- Financeiro: contas a pagar e receber, categorias, fluxo de caixa e **venda de procedimento ao paciente** (que dispara a reserva de estoque).
- Receita por procedimento e profissional (conectando à entidade da etapa 1).
- Emissão de nota fiscal fica para depois; o cadastro do paciente já guarda CPF, nome completo e endereço.
- Consentimento por medicamento ou procedimento: o item ou procedimento exige um tipo de consentimento aceito pelo paciente antes da entrega.
- Migração dos dados do Fulano (ou reconstrução, conforme a decisão da Etapa 0).

**M5 · Relatórios diários**
- Formulário estruturado de fechamento do dia, que substitui os relatórios soltos.
- Campos definidos com a equipe (atendimentos, faturamento, ocorrências, observações).
- Histórico pesquisável por data, com resumo automático por IA.

**Se não houver fonte automática de faturamento:** o M5 passa a ser a entrada oficial do faturamento diário; a integração automática fica como melhoria posterior.

**Entrega:** a equipe e o Fulano operam estoque, financeiro e fechamento diário no app.

---

### Etapa 3: Painel do empresário (M3)
**Objetivo:** o DCA enxergando a clínica de qualquer lugar.

- Home do dia: faturamento, atendimentos, ocorrências.
- Metas da equipe vs. realizado.
- Fluxo de caixa e contas a vencer.
- Estoque crítico (itens abaixo do ponto de reposição).
- Visão semanal e mensal.
- Notificações no celular (fechamento do dia, alerta de estoque, meta batida ou em risco).
- Crescimento incremental: cada bloco do painel entra quando a fonte de dados correspondente estiver pronta.

**Entrega:** DCA acompanha a clínica pelo celular, inclusive viajando. **Aqui começa o piloto (etapa 5).**

---

### Etapa 4: Clínico (M1, M2)
**Objetivo:** a visão do médico, que é o diferencial do produto.

**M1 · Integração Endo Academy AI**
- Consumir transcrições, anamneses clínica e comercial e perfil do paciente.
- Vincular tudo à entidade de paciente criada na etapa 1.
- Conformidade LGPD e sigilo para dados de saúde (consentimento, criptografia, controle de acesso por perfil).

**M2 · Paciente 360**
- Linha do tempo única: consultas, exames, procedimentos e (se entrar no escopo) treino e nutrição.
- **Ficha histórica do paciente:** bioimpedância, medicamentos, anamnese (histórico familiar, medicamentos usados, problemas crônicos, dores), procedimentos realizados e em andamento, exames com comparativo entre datas e, a cada consulta, procedimentos fechados e recusados.
- **Briefing pré-consulta por IA** (o "segundo cérebro" do paciente).
- Busca e comparação de evolução ("o que foi feito no começo vs. agora").
- Integração com o prontuário existente, se houver API (decisão da Etapa 0).

**Entrega:** o médico abre o paciente e vê tudo sem abrir consulta por consulta.

---

### Etapa 5: Piloto e medição
**Objetivo:** provar valor com dados, não com percepção.

- Definir antes do início 3 a 5 indicadores de sucesso com o DCA (por exemplo: tempo para achar um relatório, itens de estoque em falta, decisões de compra com análise, uso semanal do app).
- Linha de base (como era antes) e medição contínua.
- Rotina de feedback com DCA, Fulano e a equipe.
- Correções e ajustes priorizados.
- Registro do caso de uso para a venda aos mentorados.

---

### Etapa 6: Inteligência (M6, M7, M8)
- **M6 · Decisão de investimento:** simulador (payback, impacto no caixa, cenários) para compras como máquinas, usando os dados reais da clínica.
- **M7 · Assistente do gestor:** perguntas em linguagem natural ("como foi a semana?"), alertas proativos e resumo matinal.
- **M8 · Evolução e experiência do paciente:** relatório de resultados (como o caso dos 53 kg), exames com faixas de referência, visão para o paciente (inspirado no Hércules Society).

---

### Etapa 7: Fase 2, oferta aos mentorados
**Pré-requisito:** piloto validado, com resultados medidos.

- **F2-b · Produto:** multi-tenant maduro, onboarding de nova clínica, configuração por clínica, planos e cobrança (implementação mais recorrência), assinatura do Endo AI, esteira "Endo Academy Tech".
- **F2-a · Prontuário próprio e receitas:** só se a integração com prontuário existente não atender.
- Operação: suporte, SLA, documentação e treinamento.
- Comercial: definido fora do repositório (informação sensível).

---

## 5. Riscos e mitigação

| Risco | Impacto | Mitigação |
|---|---|---|
| Faturamento sem fonte integrável | Painel vazio | Lançamento estruturado via M5 no início |
| Base do Fulano frágil | M4 mais caro | Decidir reaproveitar/refazer na Etapa 0 |
| Endo AI sem API ou fechado | Atraso na etapa 4 | Descobrir na demo (Etapa 0); definir contrato de dados com o Beltrano |
| Capacidade de execução (outros projetos) | Atraso geral | Escopo enxuto, entregas curtas, prazos acordados após a Etapa 0 |
| Escopo crescendo (treino, nutrição, prontuário) | Fase 1 inchada | Cada item entra por decisão explícita no backlog |
| LGPD e dado de saúde | Risco jurídico na fase 2 | Base de consentimento na etapa 1; revisão jurídica antes da etapa 4 |
| Produto comoditizado | Dificuldade de venda | Etapa 4 (clínico e Endo AI) como prioridade logo após o MVP |
| Falta de prova de valor | Fase 2 trava | Indicadores e linha de base definidos antes do piloto |

## 6. Dependências e próximos passos

| Responsável | Ação | Prazo |
|---|---|---|
| DCA | Enviar curadoria intelectual (business plan, mapeamento, documento do clube de treinos e Nutri) | até 28/10 |
| Dênis | Agendar o encontro com Fulano de Tal e demo do Endo Academy AI | a definir |
| Dênis | Revisar este roadmap com o material do DCA e converter estimativas em prazos | após a Etapa 0 |
| DCA e Dênis | Validar escopo da fase 1 e indicadores do piloto | fim da Etapa 0 |

## 7. Perguntas em aberto

- Qual prontuário eletrônico a clínica usa hoje e ele tem API?
- O Endo Academy AI expõe API? Quem opera e mantém depois (Beltrano, Dênis, terceiro)?
- De onde vêm faturamento e metas hoje?
- Treino e nutrição entram na fase 1?
- Nome definitivo do produto?
- Modelo e valor do "Sistema da clínica" na esteira?
- Termo de recebimento: o toque na tela com confirmação enviada ao paciente tem validade jurídica suficiente? Pede revisão jurídica (mantida como risco; ICP-Brasil só se o jurídico exigir).
- WhatsApp, SMS e e-mail: qual provedor, qual custo por mensagem e qual consentimento do paciente (LGPD)? O que pode ir no texto da mensagem?
- Prazo de aviso de vencimento e limite de estoque: valores padrão e por item.

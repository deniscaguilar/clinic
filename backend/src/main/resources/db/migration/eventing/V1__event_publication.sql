-- Registro de publicações de eventos do Spring Modulith (estrutura v2 do PostgreSQL, spring-modulith-events-jdbc 2.1.2).
-- É a "outbox" do plano: o evento é gravado na mesma transação de quem o publica e marcado como concluído quando o
-- ouvinte termina; o que falhar fica pendente e é reenviado. Os eventos levam só ids (nunca dado pessoal).
-- Sem tenant_id e sem RLS por desenho: a tabela é de infraestrutura e o ouvinte reabre o contexto da clínica pelo id
-- do evento (RlsCatalog.globalTables).
create table eventing.event_publication
(
    id                     uuid                     not null,
    listener_id            text                     not null,
    event_type             text                     not null,
    serialized_event       text                     not null,
    publication_date       timestamp with time zone not null,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    primary key (id)
);

create index event_publication_serialized_event_hash_idx on eventing.event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx on eventing.event_publication (completion_date);

grant select, insert, update, delete on eventing.event_publication to clinic_app;

-- Profissionais da clínica e o horário de trabalho de cada um (RF4). Um médico em duas clínicas é um profissional por
-- clínica, cada um com o seu horário. Sem FK para outro schema: user_id é o id lógico do usuário (platform.app_user),
-- conferido pela aplicação por platform.api (plano, seção 14). Não se apaga: sai de uso ficando inativo.
create table registry.practitioner
(
    id                  uuid        not null primary key,
    tenant_id           uuid        not null,
    user_id             uuid,
    type                text        not null check (type in ('DOCTOR', 'NURSE', 'OTHER')),
    name                text        not null check (length(btrim(name)) between 1 and 150),
    registration_number text check (registration_number is null or length(btrim(registration_number)) between 1 and 30),
    active              boolean     not null default true,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique (tenant_id, id)
);

-- Um usuário é um profissional por clínica; sem usuário, quantos forem.
create unique index practitioner_tenant_user_key on registry.practitioner (tenant_id, user_id) where user_id is not null;
create index practitioner_tenant_name_idx on registry.practitioner (tenant_id, lower(name));

select platform.apply_tenant_rls('registry', 'practitioner');

grant select, insert, update on registry.practitioner to clinic_app;

-- Faixas de trabalho por dia da semana (1 = segunda ... 7 = domingo, como java.time.DayOfWeek), em minutos inteiros.
create table registry.practitioner_work_schedule
(
    tenant_id       uuid       not null,
    practitioner_id uuid       not null,
    day_of_week     smallint   not null check (day_of_week between 1 and 7),
    start_time      time(0)    not null,
    end_time        time(0)    not null,
    check (start_time < end_time),
    primary key (practitioner_id, day_of_week, start_time),
    foreign key (tenant_id, practitioner_id) references registry.practitioner (tenant_id, id)
);

-- Sobreposição no mesmo dia é recusada pelo banco (faixas encostadas são permitidas). A aplicação já valida; isto é a
-- última defesa. Duas transações que mexem no mesmo horário se serializam porque a atualização do profissional
-- (linha em registry.practitioner) vem antes de regravar as faixas.
create function registry.reject_overlapping_work_interval() returns trigger
    language plpgsql
as
$$
begin
    if exists (select 1
               from registry.practitioner_work_schedule w
               where w.practitioner_id = new.practitioner_id
                 and w.day_of_week = new.day_of_week
                 and w.start_time < new.end_time
                 and new.start_time < w.end_time) then
        raise exception 'work interval overlaps another one of the same practitioner and day' using errcode = '23P01';
    end if;
    return new;
end;
$$;

create trigger work_interval_no_overlap
    before insert
    on registry.practitioner_work_schedule
    for each row
execute function registry.reject_overlapping_work_interval();

select platform.apply_tenant_rls('registry', 'practitioner_work_schedule');

-- A aplicação regrava o horário inteiro: apaga e insere. Não há UPDATE.
grant select, insert, delete on registry.practitioner_work_schedule to clinic_app;

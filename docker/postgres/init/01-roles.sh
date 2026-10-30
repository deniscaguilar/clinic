#!/bin/sh
# Roda uma vez, na primeira criação do volume (ADR-0010).
# clinic_migrator: dono dos schemas, usado pelo Flyway.
# clinic_app: usado pela aplicação; sem BYPASSRLS e sem ser dono de tabela.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v migrator_password="$CLINIC_MIGRATOR_PASSWORD" \
  -v app_password="$CLINIC_APP_PASSWORD" \
  -v dbname="$POSTGRES_DB" <<'SQL'
CREATE ROLE clinic_migrator LOGIN PASSWORD :'migrator_password' NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS;
CREATE ROLE clinic_app      LOGIN PASSWORD :'app_password'      NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS;

REVOKE ALL ON DATABASE :"dbname" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dbname" TO clinic_migrator, clinic_app;

-- Ninguém cria objeto no schema public.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Um schema por módulo, mais o registro de eventos do Modulith (plan.md, seção 9.2).
CREATE SCHEMA platform      AUTHORIZATION clinic_migrator;
CREATE SCHEMA registry      AUTHORIZATION clinic_migrator;
CREATE SCHEMA notifications AUTHORIZATION clinic_migrator;
CREATE SCHEMA eventing      AUTHORIZATION clinic_migrator;

GRANT USAGE ON SCHEMA platform, registry, notifications, eventing TO clinic_app;
SQL

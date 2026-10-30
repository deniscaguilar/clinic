import { execFileSync } from 'node:child_process'

/**
 * O mock OIDC emite sempre o `sub` "local-user" (docker/oidc/oidc-config.json). Para o login entrar como administrador da
 * plataforma, este setup cria (de forma idempotente) o usuário com esse `sub` e o papel `PLATFORM_ADMIN` no Postgres do Compose.
 * Só dado de teste; nada de paciente.
 */
const SQL = `
insert into platform.app_user (id, sub, email, name)
values (gen_random_uuid(), 'local-user', 'e2e@clinic.test', 'Usuário E2E')
on conflict do nothing;
insert into platform.platform_admin (user_id)
select id from platform.app_user where sub = 'local-user'
on conflict do nothing;
`

export default function globalSetup() {
  const container = process.env.CLINIC_PG_CONTAINER ?? 'clinic-system-postgres-1'
  const user = process.env.CLINIC_PG_USER ?? 'clinic'
  execFileSync('docker', ['exec', '-i', container, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', user, '-d', 'clinic'], { input: SQL, stdio: ['pipe', 'inherit', 'inherit'] })
}

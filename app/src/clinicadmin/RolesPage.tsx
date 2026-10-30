import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField, TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { maxLength, required } from '../ui/validation'

interface RoleRow {
  id?: string
  name?: string
  permissions?: string[]
  members?: number
}

export function RolesPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { load, reload } = useLoad(
    async () => ({
      roles: (await call(api.GET('/api/v1/roles'))) ?? [],
      permissions: (await call(api.GET('/api/v1/permissions'))) ?? [],
    }),
    [api],
  )
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [editing, setEditing] = useState<RoleRow | null>(null) // null = fechado; {} = novo
  const [name, setName] = useState('')
  const [granted, setGranted] = useState<Set<string>>(new Set())
  const [submitted, setSubmitted] = useState(false)

  useEffect(() => {
    setName(editing?.name ?? '')
    setGranted(new Set(editing?.permissions ?? []))
    setSubmitted(false)
  }, [editing])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const { roles, permissions } = load.data
  const nameError = check(name, [required, maxLength(60)])

  async function save(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (nameError || !editing) return
    const body = { name: name.trim(), permissions: [...granted].sort() }
    const ok = await run(async () => {
      if (editing.id) await call(api.PUT('/api/v1/roles/{roleId}', { params: { path: { roleId: editing.id } }, body }))
      else await call(api.POST('/api/v1/roles', { body }))
    })
    if (ok) {
      setEditing(null)
      reload()
    }
  }

  const remove = (role: RoleRow) =>
    run(async () => {
      await call(api.DELETE('/api/v1/roles/{roleId}', { params: { path: { roleId: role.id! } } }))
      reload()
    })

  return (
    <section>
      <h1>{t('roles.title')}</h1>
      <button onClick={() => setEditing({})}>{t('roles.new')}</button>
      <ul>
        {roles.map((role) => (
          <li key={role.id}>
            {role.name} <span>{t('roles.members', { count: role.members ?? 0 })}</span>
            <button onClick={() => setEditing(role)}>{t('common.edit')}</button>
            <button onClick={() => void remove(role)} disabled={busy}>
              {t('common.remove')}
            </button>
          </li>
        ))}
      </ul>
      {!editing && <ProblemAlert problem={problem} />}
      {editing && (
        <form onSubmit={(e) => void save(e)} noValidate aria-label={t(editing.id ? 'roles.edit' : 'roles.new')}>
          <TextField label={t('roles.name')} value={name} onChange={setName} error={(submitted ? nameError : undefined) ?? serverMessage(problem, 'name')} />
          <fieldset>
            <legend>{t('roles.permissions')}</legend>
            {permissions.map((permission) => (
              <CheckboxField
                key={permission.code}
                label={permission.description ?? permission.code ?? ''}
                checked={granted.has(permission.code ?? '')}
                onChange={(on) =>
                  setGranted((current) => {
                    const next = new Set(current)
                    if (on) next.add(permission.code ?? '')
                    else next.delete(permission.code ?? '')
                    return next
                  })
                }
              />
            ))}
          </fieldset>
          <ProblemAlert problem={problem} />
          <button type="submit" disabled={busy}>
            {t('common.save')}
          </button>
          <button type="button" onClick={() => setEditing(null)}>
            {t('common.cancel')}
          </button>
        </form>
      )}
    </section>
  )
}

import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField, TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { email, maxLength, required } from '../ui/validation'

interface RoleOption {
  id?: string
  name?: string
}

function RoleChecklist({ roles, selected, onChange }: { roles: RoleOption[]; selected: Set<string>; onChange: (next: Set<string>) => void }) {
  return (
    <>
      {roles.map((role) => (
        <CheckboxField
          key={role.id}
          label={role.name ?? ''}
          checked={selected.has(role.id ?? '')}
          onChange={(on) => {
            const next = new Set(selected)
            if (on) next.add(role.id ?? '')
            else next.delete(role.id ?? '')
            onChange(next)
          }}
        />
      ))}
    </>
  )
}

export function UsersPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { load, reload } = useLoad(
    async () => ({ members: (await call(api.GET('/api/v1/users'))) ?? [], roles: (await call(api.GET('/api/v1/roles'))) ?? [] }),
    [api],
  )
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteName, setInviteName] = useState('')
  const [inviteRoles, setInviteRoles] = useState<Set<string>>(new Set())
  const [submitted, setSubmitted] = useState(false)
  const [edited, setEdited] = useState<Record<string, Set<string>>>({})

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const { members, roles } = load.data
  const errors = { email: check(inviteEmail, [required, maxLength(254), email]), name: check(inviteName, [required, maxLength(120)]) }

  async function invite(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (errors.email || errors.name) return
    const ok = await run(() => call(api.POST('/api/v1/users', { body: { email: inviteEmail.trim(), name: inviteName.trim(), roleIds: [...inviteRoles] } })))
    if (ok) {
      setInviteEmail('')
      setInviteName('')
      setInviteRoles(new Set())
      setSubmitted(false)
      reload()
    }
  }

  const act = (action: () => Promise<unknown>) =>
    run(async () => {
      await action()
      reload()
    })

  return (
    <section>
      <h1>{t('users.title')}</h1>
      <ul>
        {members.map((member) => {
          const id = member.membershipId ?? ''
          const current = edited[id] ?? new Set(roles.filter((r) => member.roles?.some((own) => own.id === r.id)).map((r) => r.id ?? ''))
          return (
            <li key={id}>
              <strong>{member.name}</strong> ({member.email}) <span>{t(`users.status.${member.status}`)}</span>
              <RoleChecklist roles={roles} selected={current} onChange={(next) => setEdited((all) => ({ ...all, [id]: next }))} />
              <button disabled={busy} onClick={() => void act(() => call(api.PATCH('/api/v1/users/{membershipId}', { params: { path: { membershipId: id } }, body: { roleIds: [...current] } })))}>
                {t('users.saveRoles')}
              </button>
              {member.status === 'PENDING' && (
                <button disabled={busy} onClick={() => void act(() => call(api.POST('/api/v1/users/{membershipId}/resend-invitation', { params: { path: { membershipId: id } } })))}>
                  {t('users.resend')}
                </button>
              )}
              {member.status !== 'INACTIVE' && (
                <button disabled={busy} onClick={() => void act(() => call(api.POST('/api/v1/users/{membershipId}/deactivate', { params: { path: { membershipId: id } } })))}>
                  {t('users.deactivate')}
                </button>
              )}
            </li>
          )
        })}
      </ul>
      <form onSubmit={(e) => void invite(e)} noValidate aria-label={t('users.invite')}>
        <h2>{t('users.invite')}</h2>
        <TextField label={t('users.email')} value={inviteEmail} onChange={setInviteEmail} type="email" error={(submitted ? errors.email : undefined) ?? serverMessage(problem, 'email')} />
        <TextField label={t('users.name')} value={inviteName} onChange={setInviteName} error={(submitted ? errors.name : undefined) ?? serverMessage(problem, 'name')} />
        <RoleChecklist roles={roles} selected={inviteRoles} onChange={setInviteRoles} />
        <ProblemAlert problem={problem} />
        <button type="submit" disabled={busy}>
          {t('users.sendInvite')}
        </button>
      </form>
    </section>
  )
}

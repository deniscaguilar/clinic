import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { maxLength, required } from '../ui/validation'

export function AdminClinicDetail() {
  const api = useApi()
  const { t } = useTranslation()
  const clinicId = useParams().clinicId ?? ''
  const { load, reload } = useLoad(() => call(api.GET('/api/v1/admin/clinics/{clinicId}', { params: { path: { clinicId } } })), [api, clinicId])
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [name, setName] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [saved, setSaved] = useState(false)
  const loaded = load.state === 'ready' ? load.data : undefined
  useEffect(() => {
    if (loaded?.name !== undefined) setName(loaded.name)
  }, [loaded?.name])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const clinic = load.data
  if (!clinic) return null
  const nameError = check(name, [required, maxLength(120)])

  async function rename(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    setSaved(false)
    if (nameError) return
    if (await run(() => call(api.PATCH('/api/v1/admin/clinics/{clinicId}', { params: { path: { clinicId } }, body: { name: name.trim() } })))) {
      setSaved(true)
      reload()
    }
  }

  const toggle = () =>
    run(async () => {
      const path = clinic.status === 'ACTIVE' ? '/api/v1/admin/clinics/{clinicId}/deactivate' : '/api/v1/admin/clinics/{clinicId}/activate'
      await call(api.POST(path, { params: { path: { clinicId } } }))
      reload()
    })

  const resend = (membershipId: string) =>
    run(async () => {
      await call(api.POST('/api/v1/admin/clinics/{clinicId}/admins/{membershipId}/resend-invitation', { params: { path: { clinicId, membershipId } } }))
      reload()
    })

  return (
    <section>
      <h1>{clinic.name}</h1>
      <p>{t(`admin.status.${clinic.status}`)}</p>
      <form onSubmit={(e) => void rename(e)} noValidate>
        <TextField label={t('admin.clinics.name')} value={name} onChange={setName} error={(submitted ? nameError : undefined) ?? serverMessage(problem, 'name')} />
        <button type="submit" disabled={busy}>
          {t('common.save')}
        </button>
        {saved && <p>{t('admin.clinics.saved')}</p>}
      </form>
      <button onClick={() => void toggle()} disabled={busy}>
        {t(clinic.status === 'ACTIVE' ? 'admin.clinics.deactivate' : 'admin.clinics.activate')}
      </button>
      <h2>{t('admin.clinics.admins')}</h2>
      <ul>
        {(clinic.admins ?? []).map((admin) => (
          <li key={admin.membershipId}>
            {admin.name} ({admin.email}) <span>{t(`admin.membership.${admin.status}`)}</span>
            {admin.status === 'PENDING' && admin.membershipId && (
              <button onClick={() => void resend(admin.membershipId!)} disabled={busy}>
                {t('admin.clinics.resend')}
              </button>
            )}
          </li>
        ))}
      </ul>
      <ProblemAlert problem={problem} />
    </section>
  )
}

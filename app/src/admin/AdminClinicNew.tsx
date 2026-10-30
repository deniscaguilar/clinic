import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { email, maxLength, required } from '../ui/validation'


export function AdminClinicNew() {
  const api = useApi()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [name, setName] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [adminName, setAdminName] = useState('')
  const [submitted, setSubmitted] = useState(false)

  const errors = {
    name: check(name, [required, maxLength(120)]),
    adminEmail: check(adminEmail, [required, maxLength(254), email]),
    adminName: check(adminName, [required, maxLength(120)]),
  }
  const show = (field: keyof typeof errors) => (submitted ? errors[field] : undefined) ?? serverMessage(problem, field)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (Object.values(errors).some(Boolean)) return
    await run(async () => {
      const created = await call(api.POST('/api/v1/admin/clinics', { body: { name: name.trim(), adminEmail: adminEmail.trim(), adminName: adminName.trim() } }))
      navigate(`/admin/clinics/${created?.id}`)
    })
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate>
      <h1>{t('admin.clinics.new')}</h1>
      <TextField label={t('admin.clinics.name')} value={name} onChange={setName} error={show('name')} />
      <TextField label={t('admin.clinics.adminEmail')} value={adminEmail} onChange={setAdminEmail} error={show('adminEmail')} type="email" />
      <TextField label={t('admin.clinics.adminName')} value={adminName} onChange={setAdminName} error={show('adminName')} />
      <ProblemAlert problem={problem} />
      <button type="submit" disabled={busy}>
        {t('admin.clinics.create')}
      </button>
    </form>
  )
}

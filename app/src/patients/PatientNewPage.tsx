import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField, TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { email as emailRule, maxLength, required } from '../ui/validation'
import { isValidCpf, onlyDigits } from './cpf'

const cpfRule = (value: string) => (isValidCpf(value) ? null : { key: 'patients.cpfInvalid' })

export function PatientNewPage() {
  const api = useApi()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { load } = useLoad(async () => {
    const types = (await call(api.GET('/api/v1/consent-types', { params: { query: { active: true } } }))) ?? []
    return Promise.all(types.map(async (type) => ({ summary: type, detail: await call(api.GET('/api/v1/consent-types/{id}', { params: { path: { id: type.id! } } })) })))
  }, [api])
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [f, setF] = useState({ cpf: '', fullName: '', birthDate: '', whatsapp: '', email: '', landline: '', zipCode: '', street: '', number: '', complement: '', district: '', city: '', state: '' })
  const [accepted, setAccepted] = useState<Set<string>>(new Set())
  const [submitted, setSubmitted] = useState(false)
  const set = (key: keyof typeof f) => (value: string) => setF((current) => ({ ...current, [key]: value }))

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const types = load.data
  const missingRequired = types.filter((x) => x.summary.requiredOnSignup && !accepted.has(x.summary.id ?? ''))

  const errors = {
    cpf: check(f.cpf, [required, cpfRule]),
    fullName: check(f.fullName, [required, maxLength(150)]),
    birthDate: check(f.birthDate, [required]),
    email: f.email.trim() ? check(f.email, [maxLength(254), emailRule]) : undefined,
    zipCode: check(f.zipCode, [required]),
    street: check(f.street, [required]),
    number: check(f.number, [required]),
    district: check(f.district, [required]),
    city: check(f.city, [required]),
    state: check(f.state, [required]),
  }
  const show = (field: keyof typeof errors) => (submitted ? errors[field] : undefined) ?? serverMessage(problem, field)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    // Sem aceitar todos os consentimentos obrigatórios nada é enviado (RF5a); o servidor confere de novo.
    if (Object.values(errors).some(Boolean) || missingRequired.length > 0) return
    const blank = (value: string) => value.trim() || null
    await run(async () => {
      const created = await call(
        api.POST('/api/v1/patients', {
          body: {
            cpf: onlyDigits(f.cpf),
            fullName: f.fullName.trim(),
            birthDate: f.birthDate,
            whatsapp: blank(f.whatsapp),
            email: blank(f.email),
            landline: blank(f.landline),
            address: { zipCode: f.zipCode.trim(), street: f.street.trim(), number: f.number.trim(), complement: blank(f.complement), district: f.district.trim(), city: f.city.trim(), state: f.state.trim().toUpperCase() },
            consents: [...accepted].map((typeId) => ({ typeId })),
          },
        }),
      )
      navigate(`/patients/${created?.id}`)
    })
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate aria-label={t('patients.new')}>
      <h1>{t('patients.new')}</h1>
      <TextField label={t('patients.cpf')} value={f.cpf} onChange={set('cpf')} error={show('cpf')} />
      <TextField label={t('patients.fullName')} value={f.fullName} onChange={set('fullName')} error={show('fullName')} />
      <TextField label={t('patients.birthDate')} value={f.birthDate} onChange={set('birthDate')} type="date" error={show('birthDate')} />
      <TextField label={t('patients.whatsapp')} value={f.whatsapp} onChange={set('whatsapp')} type="tel" />
      <TextField label={t('patients.email')} value={f.email} onChange={set('email')} type="email" error={show('email')} />
      <TextField label={t('patients.landline')} value={f.landline} onChange={set('landline')} type="tel" />
      <fieldset>
        <legend>{t('patients.address')}</legend>
        <TextField label={t('patients.zipCode')} value={f.zipCode} onChange={set('zipCode')} error={show('zipCode')} />
        <TextField label={t('patients.street')} value={f.street} onChange={set('street')} error={show('street')} />
        <TextField label={t('patients.number')} value={f.number} onChange={set('number')} error={show('number')} />
        <TextField label={t('patients.complement')} value={f.complement} onChange={set('complement')} />
        <TextField label={t('patients.district')} value={f.district} onChange={set('district')} error={show('district')} />
        <TextField label={t('patients.city')} value={f.city} onChange={set('city')} error={show('city')} />
        <TextField label={t('patients.state')} value={f.state} onChange={set('state')} error={show('state')} />
      </fieldset>
      <fieldset>
        <legend>{t('patients.consents')}</legend>
        {types.map(({ summary, detail }) => (
          <div key={summary.id}>
            <CheckboxField
              label={summary.requiredOnSignup ? t('patients.consentRequired', { name: summary.name }) : (summary.name ?? '')}
              checked={accepted.has(summary.id ?? '')}
              onChange={(on) =>
                setAccepted((current) => {
                  const next = new Set(current)
                  if (on) next.add(summary.id ?? '')
                  else next.delete(summary.id ?? '')
                  return next
                })
              }
            />
            <details>
              <summary>{t('patients.readConsent', { name: summary.name })}</summary>
              <p>{detail?.currentVersion?.text}</p>
            </details>
          </div>
        ))}
        {submitted && missingRequired.length > 0 && <p role="alert">{t('errors.CONSENT_REQUIRED')}</p>}
      </fieldset>
      <ProblemAlert problem={problem} />
      <button type="submit" disabled={busy}>
        {t('patients.register')}
      </button>
    </form>
  )
}

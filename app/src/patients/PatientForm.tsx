import { useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { Problem } from '../api/errors'
import { TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useFormErrors } from '../ui/useFormErrors'
import { email as emailRule, maxLength, required } from '../ui/validation'
import { formatCpf, isValidCpf } from './cpf'

export interface PatientValues {
  cpf: string
  fullName: string
  birthDate: string
  whatsapp: string
  email: string
  landline: string
  zipCode: string
  street: string
  number: string
  complement: string
  district: string
  city: string
  state: string
}

export const emptyPatient: PatientValues = { cpf: '', fullName: '', birthDate: '', whatsapp: '', email: '', landline: '', zipCode: '', street: '', number: '', complement: '', district: '', city: '', state: '' }

interface PatientData {
  cpf?: string
  fullName?: string
  birthDate?: string
  whatsapp?: string | null
  email?: string | null
  landline?: string | null
  address?: { zipCode?: string; street?: string; number?: string; complement?: string | null; district?: string; city?: string; state?: string }
}

/** Valores do formulário a partir do paciente devolvido pela API (o CPF aparece formatado). */
export function toValues(p: PatientData): PatientValues {
  return {
    cpf: formatCpf(p.cpf),
    fullName: p.fullName ?? '',
    birthDate: p.birthDate ?? '',
    whatsapp: p.whatsapp ?? '',
    email: p.email ?? '',
    landline: p.landline ?? '',
    zipCode: p.address?.zipCode ?? '',
    street: p.address?.street ?? '',
    number: p.address?.number ?? '',
    complement: p.address?.complement ?? '',
    district: p.address?.district ?? '',
    city: p.address?.city ?? '',
    state: p.address?.state ?? '',
  }
}

const blank = (value: string) => value.trim() || null

/** Dados cadastrais no formato do contrato (sem CPF nem consentimentos, que cada tela decide). */
export function toRequest(v: PatientValues) {
  return {
    fullName: v.fullName.trim(),
    birthDate: v.birthDate,
    whatsapp: blank(v.whatsapp),
    email: blank(v.email),
    landline: blank(v.landline),
    address: { zipCode: v.zipCode.trim(), street: v.street.trim(), number: v.number.trim(), complement: blank(v.complement), district: v.district.trim(), city: v.city.trim(), state: v.state.trim().toUpperCase() },
  }
}

const cpfRule = (value: string) => (isValidCpf(value) ? null : { key: 'patients.cpfInvalid' })

interface Props {
  title: string
  submitLabel: string
  initial: PatientValues
  /** `false` mostra o CPF somente leitura (sem permissão para trocá-lo). */
  cpfEditable: boolean
  /** Bloqueia o envio por uma regra da tela (ex.: consentimento obrigatório não aceito). */
  blocked?: boolean
  extra?: (context: { submitted: boolean }) => ReactNode
  busy: boolean
  problem: Problem | null
  onSubmit: (values: PatientValues) => unknown
}

export function PatientForm({ title, submitLabel, initial, cpfEditable, blocked = false, extra, busy, problem, onSubmit }: Props) {
  const { t } = useTranslation()
  const { check, serverMessage } = useFormErrors()
  const [f, setF] = useState(initial)
  const [submitted, setSubmitted] = useState(false)
  const set = (key: keyof PatientValues) => (value: string) => setF((current) => ({ ...current, [key]: value }))

  const errors = {
    cpf: cpfEditable ? check(f.cpf, [required, cpfRule]) : undefined,
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
    if (Object.values(errors).some(Boolean) || blocked) return
    await onSubmit(f)
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate aria-label={title}>
      <h1>{title}</h1>
      {cpfEditable ? (
        <TextField label={t('patients.cpf')} value={f.cpf} onChange={set('cpf')} error={show('cpf')} />
      ) : (
        <TextField label={t('patients.cpf')} value={f.cpf} onChange={() => {}} readOnly />
      )}
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
      {extra?.({ submitted })}
      <ProblemAlert problem={problem} />
      <button type="submit" disabled={busy}>
        {submitLabel}
      </button>
    </form>
  )
}

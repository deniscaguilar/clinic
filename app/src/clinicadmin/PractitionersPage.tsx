import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField, TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { maxLength, required } from '../ui/validation'

const TYPES = ['DOCTOR', 'NURSE', 'OTHER'] as const
const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const

interface Interval {
  dayOfWeek: string
  start: string
  end: string
}

interface PractitionerRow {
  id?: string
  name?: string
  type?: string
  registrationNumber?: string | null
  userId?: string | null
  active?: boolean
  workSchedule?: Interval[]
}

export function PractitionersPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { load, reload } = useLoad(
    async () => ({ items: (await call(api.GET('/api/v1/practitioners'))) ?? [], members: (await call(api.GET('/api/v1/users'))) ?? [] }),
    [api],
  )
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [editing, setEditing] = useState<PractitionerRow | null>(null)
  const [name, setName] = useState('')
  const [type, setType] = useState<string>('DOCTOR')
  const [registration, setRegistration] = useState('')
  const [userId, setUserId] = useState('')
  const [active, setActive] = useState(true)
  const [schedule, setSchedule] = useState<Interval[]>([])
  const [submitted, setSubmitted] = useState(false)

  useEffect(() => {
    setName(editing?.name ?? '')
    setType(editing?.type ?? 'DOCTOR')
    setRegistration(editing?.registrationNumber ?? '')
    setUserId(editing?.userId ?? '')
    setActive(editing?.active ?? true)
    setSchedule(editing?.workSchedule ?? [])
    setSubmitted(false)
  }, [editing])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const errors = { name: check(name, [required, maxLength(150)]), registrationNumber: check(registration, [maxLength(30)]) }
  const show = (field: keyof typeof errors) => (submitted ? errors[field] : undefined) ?? serverMessage(problem, field)
  const updateInterval = (index: number, patch: Partial<Interval>) => setSchedule((rows) => rows.map((row, i) => (i === index ? { ...row, ...patch } : row)))

  async function save(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (errors.name || errors.registrationNumber || !editing) return
    const body = { name: name.trim(), type, registrationNumber: registration.trim() || null, userId: userId || null, workSchedule: schedule }
    const ok = await run(async () => {
      if (editing.id) await call(api.PUT('/api/v1/practitioners/{id}', { params: { path: { id: editing.id } }, body: { ...body, active } }))
      else await call(api.POST('/api/v1/practitioners', { body }))
    })
    if (ok) {
      setEditing(null)
      reload()
    }
  }

  return (
    <section>
      <h1>{t('practitioners.title')}</h1>
      <button onClick={() => setEditing({})}>{t('practitioners.new')}</button>
      <ul>
        {load.data.items.map((p) => (
          <li key={p.id}>
            {p.name} ({t(`practitioners.types.${p.type}`)}) {!p.active && <span>{t('common.inactive')}</span>}
            <button
              onClick={() =>
                setEditing({
                  ...p,
                  workSchedule: (p.workSchedule ?? []).map((i) => ({ dayOfWeek: i.dayOfWeek ?? 'MONDAY', start: i.start ?? '', end: i.end ?? '' })),
                })
              }
            >{t('common.edit')}</button>
          </li>
        ))}
      </ul>
      {!editing && <ProblemAlert problem={problem} />}
      {editing && (
        <form onSubmit={(e) => void save(e)} noValidate aria-label={t(editing.id ? 'practitioners.edit' : 'practitioners.new')}>
          <TextField label={t('practitioners.name')} value={name} onChange={setName} error={show('name')} />
          <label htmlFor="practitioner-type">{t('practitioners.type')}</label>
          <select id="practitioner-type" value={type} onChange={(e) => setType(e.target.value)}>
            {TYPES.map((value) => (
              <option key={value} value={value}>
                {t(`practitioners.types.${value}`)}
              </option>
            ))}
          </select>
          <TextField label={t('practitioners.registration')} value={registration} onChange={setRegistration} error={show('registrationNumber')} />
          <label htmlFor="practitioner-user">{t('practitioners.user')}</label>
          <select id="practitioner-user" value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">{t('practitioners.noUser')}</option>
            {load.data.members
              .filter((m) => m.status === 'ACTIVE')
              .map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.name}
                </option>
              ))}
          </select>
          <fieldset>
            <legend>{t('practitioners.schedule')}</legend>
            {schedule.map((row, index) => (
              <div key={index}>
                <select aria-label={t('practitioners.day')} value={row.dayOfWeek} onChange={(e) => updateInterval(index, { dayOfWeek: e.target.value })}>
                  {DAYS.map((day) => (
                    <option key={day} value={day}>
                      {t(`practitioners.days.${day}`)}
                    </option>
                  ))}
                </select>
                <input aria-label={t('practitioners.start')} type="time" value={row.start} onChange={(e) => updateInterval(index, { start: e.target.value })} />
                <input aria-label={t('practitioners.end')} type="time" value={row.end} onChange={(e) => updateInterval(index, { end: e.target.value })} />
                <button type="button" onClick={() => setSchedule((rows) => rows.filter((_, i) => i !== index))}>
                  {t('common.remove')}
                </button>
              </div>
            ))}
            <button type="button" onClick={() => setSchedule((rows) => [...rows, { dayOfWeek: 'MONDAY', start: '08:00', end: '12:00' }])}>
              {t('practitioners.addInterval')}
            </button>
          </fieldset>
          {editing.id && <CheckboxField label={t('common.active')} checked={active} onChange={setActive} />}
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

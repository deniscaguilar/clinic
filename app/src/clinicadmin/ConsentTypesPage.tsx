import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField, TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { maxLength, required } from '../ui/validation'

interface TypeDetail {
  id?: string
  name?: string
  requiredOnSignup?: boolean
  controlsMessaging?: boolean
  active?: boolean
  currentVersion?: { number?: number; text?: string }
  versions?: { number?: number; text?: string }[]
}

export function ConsentTypesPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { load, reload } = useLoad(
    async () => ({ types: (await call(api.GET('/api/v1/consent-types'))) ?? [], offers: (await call(api.GET('/api/v1/consent-templates'))) ?? [] }),
    [api],
  )
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [editing, setEditing] = useState<TypeDetail | null>(null)
  const [name, setName] = useState('')
  const [text, setText] = useState('')
  const [requiredOnSignup, setRequiredOnSignup] = useState(false)
  const [controlsMessaging, setControlsMessaging] = useState(false)
  const [active, setActive] = useState(true)
  const [submitted, setSubmitted] = useState(false)

  useEffect(() => {
    setName(editing?.name ?? '')
    setText(editing?.currentVersion?.text ?? '')
    setRequiredOnSignup(!!editing?.requiredOnSignup)
    setControlsMessaging(!!editing?.controlsMessaging)
    setActive(editing?.active ?? true)
    setSubmitted(false)
  }, [editing])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const errors = { name: check(name, [required, maxLength(120)]), text: check(text, [required, maxLength(20000)]) }
  const show = (field: keyof typeof errors) => (submitted ? errors[field] : undefined) ?? serverMessage(problem, field)

  async function open(id: string | undefined) {
    if (!id) return
    await run(async () => setEditing((await call(api.GET('/api/v1/consent-types/{id}', { params: { path: { id } } }))) ?? null))
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (errors.name || errors.text || !editing) return
    const body = { name: name.trim(), text: text.trim(), requiredOnSignup, controlsMessaging }
    const ok = await run(async () => {
      if (editing.id) await call(api.PUT('/api/v1/consent-types/{id}', { params: { path: { id: editing.id } }, body: { ...body, active } }))
      else await call(api.POST('/api/v1/consent-types', { body }))
    })
    if (ok) {
      setEditing(null)
      reload()
    }
  }

  const importTemplate = (templateId: string | undefined) =>
    run(async () => {
      await call(api.POST('/api/v1/consent-types/import-template', { body: { templateId: templateId ?? null } }))
      reload()
    })

  return (
    <section>
      <h1>{t('consentTypes.title')}</h1>
      <button onClick={() => setEditing({})}>{t('consentTypes.new')}</button>
      <ul>
        {load.data.types.map((type) => (
          <li key={type.id}>
            {type.name} <span>{t('consentTypes.version', { number: type.currentVersion?.number ?? 1 })}</span>{' '}
            {type.requiredOnSignup && <span>{t('consentTypes.required')}</span>} {!type.active && <span>{t('common.inactive')}</span>}
            <button onClick={() => void open(type.id)}>{t('common.edit')}</button>
          </li>
        ))}
      </ul>
      {load.data.offers.length > 0 && (
        <div>
          <h2>{t('consentTypes.offers')}</h2>
          <ul>
            {load.data.offers.map((offer) => (
              <li key={offer.id}>
                {offer.name}
                <button disabled={busy} onClick={() => void importTemplate(offer.id)}>
                  {t('consentTypes.import')}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {!editing && <ProblemAlert problem={problem} />}
      {editing && (
        <form onSubmit={(e) => void save(e)} noValidate aria-label={t(editing.id ? 'consentTypes.edit' : 'consentTypes.new')}>
          <TextField label={t('consentTypes.name')} value={name} onChange={setName} error={show('name')} />
          <TextField label={t('consentTypes.text')} value={text} onChange={setText} error={show('text')} multiline />
          {editing.id && <p>{t('consentTypes.newVersionHint')}</p>}
          <CheckboxField label={t('consentTypes.requiredOnSignup')} checked={requiredOnSignup} onChange={setRequiredOnSignup} />
          <CheckboxField label={t('consentTypes.controlsMessaging')} checked={controlsMessaging} onChange={setControlsMessaging} />
          {editing.id && <CheckboxField label={t('common.active')} checked={active} onChange={setActive} />}
          {editing.id && (
            <ul aria-label={t('consentTypes.versions')}>
              {(editing.versions ?? []).map((v) => (
                <li key={v.number}>{t('consentTypes.version', { number: v.number ?? 0 })}</li>
              ))}
            </ul>
          )}
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

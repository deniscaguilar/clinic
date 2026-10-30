import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField, TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { maxLength, required } from '../ui/validation'

/** Cria (sem `id` na rota) ou edita um modelo de consentimento da plataforma. */
export function AdminTemplateForm() {
  const api = useApi()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const id = useParams().id
  const { load } = useLoad(async () => (id ? await call(api.GET('/api/v1/admin/consent-templates/{id}', { params: { path: { id } } })) : undefined), [api, id])
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [name, setName] = useState('')
  const [text, setText] = useState('')
  const [requiredOnSignup, setRequiredOnSignup] = useState(false)
  const [controlsMessaging, setControlsMessaging] = useState(false)
  const [active, setActive] = useState(true)
  const [submitted, setSubmitted] = useState(false)
  const [saved, setSaved] = useState(false)
  const loaded = load.state === 'ready' ? load.data : undefined
  useEffect(() => {
    if (!loaded) return
    setName(loaded.name ?? '')
    setText(loaded.text ?? '')
    setRequiredOnSignup(!!loaded.requiredOnSignup)
    setControlsMessaging(!!loaded.controlsMessaging)
    setActive(loaded.active ?? true)
  }, [loaded])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />

  const errors = { name: check(name, [required, maxLength(120)]), text: check(text, [required, maxLength(20000)]) }
  const show = (field: keyof typeof errors) => (submitted ? errors[field] : undefined) ?? serverMessage(problem, field)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    setSaved(false)
    if (errors.name || errors.text) return
    const body = { name: name.trim(), text: text.trim(), requiredOnSignup, controlsMessaging }
    const ok = await run(async () => {
      if (id) await call(api.PUT('/api/v1/admin/consent-templates/{id}', { params: { path: { id } }, body: { ...body, active } }))
      else {
        const created = await call(api.POST('/api/v1/admin/consent-templates', { body }))
        navigate(`/admin/consent-templates/${created?.id}`)
      }
    })
    if (ok && id) setSaved(true)
  }

  return (
    <form onSubmit={(e) => void submit(e)} noValidate>
      <h1>{t(id ? 'admin.templates.edit' : 'admin.templates.new')}</h1>
      <TextField label={t('admin.templates.name')} value={name} onChange={setName} error={show('name')} />
      <TextField label={t('admin.templates.text')} value={text} onChange={setText} error={show('text')} multiline />
      <CheckboxField label={t('admin.templates.requiredOnSignup')} checked={requiredOnSignup} onChange={setRequiredOnSignup} />
      <CheckboxField label={t('admin.templates.controlsMessaging')} checked={controlsMessaging} onChange={setControlsMessaging} />
      {id && <CheckboxField label={t('admin.templates.active')} checked={active} onChange={setActive} />}
      <ProblemAlert problem={problem} />
      <button type="submit" disabled={busy}>
        {t('common.save')}
      </button>
      {saved && <p>{t('admin.templates.saved')}</p>}
    </form>
  )
}

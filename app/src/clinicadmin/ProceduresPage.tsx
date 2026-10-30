import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField, TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { maxLength, required } from '../ui/validation'

interface ProcedureRow {
  id?: string
  name?: string
  description?: string | null
  active?: boolean
}

export function ProceduresPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { load, reload } = useLoad(async () => (await call(api.GET('/api/v1/procedures'))) ?? [], [api])
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [editing, setEditing] = useState<ProcedureRow | null>(null)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [active, setActive] = useState(true)
  const [submitted, setSubmitted] = useState(false)

  useEffect(() => {
    setName(editing?.name ?? '')
    setDescription(editing?.description ?? '')
    setActive(editing?.active ?? true)
    setSubmitted(false)
  }, [editing])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const errors = { name: check(name, [required, maxLength(120)]), description: check(description, [maxLength(500)]) }
  const show = (field: keyof typeof errors) => (submitted ? errors[field] : undefined) ?? serverMessage(problem, field)

  async function save(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (errors.name || errors.description || !editing) return
    const body = { name: name.trim(), description: description.trim() || null }
    const ok = await run(async () => {
      if (editing.id) await call(api.PUT('/api/v1/procedures/{id}', { params: { path: { id: editing.id } }, body: { ...body, active } }))
      else await call(api.POST('/api/v1/procedures', { body }))
    })
    if (ok) {
      setEditing(null)
      reload()
    }
  }

  return (
    <section>
      <h1>{t('procedures.title')}</h1>
      <button onClick={() => setEditing({})}>{t('procedures.new')}</button>
      {load.data.length === 0 && <p>{t('procedures.empty')}</p>}
      <ul>
        {load.data.map((procedure) => (
          <li key={procedure.id}>
            {procedure.name} {!procedure.active && <span>{t('common.inactive')}</span>}
            <button onClick={() => setEditing(procedure)}>{t('common.edit')}</button>
          </li>
        ))}
      </ul>
      {!editing && <ProblemAlert problem={problem} />}
      {editing && (
        <form onSubmit={(e) => void save(e)} noValidate aria-label={t(editing.id ? 'procedures.edit' : 'procedures.new')}>
          <TextField label={t('procedures.name')} value={name} onChange={setName} error={show('name')} />
          <TextField label={t('procedures.description')} value={description} onChange={setDescription} error={show('description')} multiline />
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

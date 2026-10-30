import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useLoad } from '../ui/hooks'

const PAGE_SIZE = 20

export function AuditPage() {
  const api = useApi()
  const { t, i18n } = useTranslation()
  const [action, setAction] = useState('')
  const [entityType, setEntityType] = useState('')
  const [applied, setApplied] = useState({ action: '', entityType: '' })
  const [page, setPage] = useState(0)
  const { load } = useLoad(
    () =>
      call(
        api.GET('/api/v1/audit-events', {
          params: { query: { page, size: PAGE_SIZE, ...(applied.action ? { action: applied.action } : {}), ...(applied.entityType ? { entityType: applied.entityType } : {}) } },
        }),
      ),
    [api, page, applied],
  )
  const format = (value?: string) => (value ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'short', timeStyle: 'medium', timeZone: 'America/Sao_Paulo' }).format(new Date(value)) : '')

  return (
    <section>
      <h1>{t('audit.title')}</h1>
      <form
        aria-label={t('audit.filters')}
        onSubmit={(e) => {
          e.preventDefault()
          setPage(0)
          setApplied({ action: action.trim(), entityType: entityType.trim() })
        }}
      >
        <TextField label={t('audit.action')} value={action} onChange={setAction} />
        <TextField label={t('audit.entityType')} value={entityType} onChange={setEntityType} />
        <button type="submit">{t('audit.filter')}</button>
      </form>
      {load.state === 'loading' && <p>{t('common.loading')}</p>}
      {load.state === 'error' && <ProblemAlert problem={load.problem} />}
      {load.state === 'ready' && (
        <>
          {(load.data?.items ?? []).length === 0 && <p>{t('audit.empty')}</p>}
          <ul>
            {(load.data?.items ?? []).map((event) => (
              <li key={event.id}>
                {format(event.occurredAt)} · {event.action} · {event.entityType} · {event.outcome}
              </li>
            ))}
          </ul>
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            {t('common.previous')}
          </button>
          <button disabled={(page + 1) * PAGE_SIZE >= (load.data?.total ?? 0)} onClick={() => setPage((p) => p + 1)}>
            {t('common.next')}
          </button>
        </>
      )}
    </section>
  )
}

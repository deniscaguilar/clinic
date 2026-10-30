import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useLoad } from '../ui/hooks'

const PAGE_SIZE = 20
const STATUSES = ['PENDING', 'SENT', 'FAILED', 'BLOCKED_NO_CONSENT', 'CANCELLED'] as const
const CHANNELS = ['EMAIL', 'WHATSAPP'] as const
type Status = (typeof STATUSES)[number]
type Channel = (typeof CHANNELS)[number]

export function NotificationsPage() {
  const api = useApi()
  const { t, i18n } = useTranslation()
  const [status, setStatus] = useState<Status | ''>('')
  const [channel, setChannel] = useState<Channel | ''>('')
  const [applied, setApplied] = useState<{ status: Status | ''; channel: Channel | '' }>({ status: '', channel: '' })
  const [page, setPage] = useState(0)
  const { load } = useLoad(
    () =>
      call(
        api.GET('/api/v1/notifications', {
          params: { query: { page, size: PAGE_SIZE, ...(applied.status ? { status: applied.status } : {}), ...(applied.channel ? { channel: applied.channel } : {}) } },
        }),
      ),
    [api, page, applied],
  )
  const format = (value?: string | null) =>
    value ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Sao_Paulo' }).format(new Date(value)) : ''

  return (
    <section>
      <h1>{t('notifications.title')}</h1>
      <form
        aria-label={t('notifications.filters')}
        onSubmit={(e) => {
          e.preventDefault()
          setPage(0)
          setApplied({ status, channel })
        }}
      >
        <label htmlFor="notification-status">{t('notifications.statusLabel')}</label>
        <select id="notification-status" value={status} onChange={(e) => setStatus(e.target.value as Status | '')}>
          <option value="">{t('notifications.all')}</option>
          {STATUSES.map((value) => (
            <option key={value} value={value}>
              {t(`notifications.status.${value}`)}
            </option>
          ))}
        </select>
        <label htmlFor="notification-channel">{t('notifications.channelLabel')}</label>
        <select id="notification-channel" value={channel} onChange={(e) => setChannel(e.target.value as Channel | '')}>
          <option value="">{t('notifications.all')}</option>
          {CHANNELS.map((value) => (
            <option key={value} value={value}>
              {t(`notifications.channel.${value}`)}
            </option>
          ))}
        </select>
        <button type="submit">{t('notifications.filter')}</button>
      </form>
      {load.state === 'loading' && <p>{t('common.loading')}</p>}
      {load.state === 'error' && <ProblemAlert problem={load.problem} />}
      {load.state === 'ready' && (
        <>
          {(load.data?.items ?? []).length === 0 && <p>{t('notifications.empty')}</p>}
          <ul>
            {(load.data?.items ?? []).map((n) => (
              <li key={n.id}>
                <span>{t(`notifications.kind.${n.kind}`)}</span> · <span>{t(`notifications.channel.${n.channel}`)}</span> · <span>{t(`notifications.status.${n.status}`)}</span> ·{' '}
                {format(n.createdAt)} · {t('notifications.attempts', { count: n.attempts ?? 0 })}
                {n.lastErrorCode && <span> · {n.lastErrorCode}</span>}
                {n.status === 'PENDING' && n.nextAttemptAt && <span> · {t('notifications.nextAttempt', { when: format(n.nextAttemptAt) })}</span>}{' '}
                <Link to={`/patients/${n.patientId}`}>{t('notifications.viewPatient')}</Link>
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

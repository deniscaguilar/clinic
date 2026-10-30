import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { useSession } from '../session/SessionProvider'
import { TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { required, type Rule } from '../ui/validation'
import { formatCpf, isValidCpf, onlyDigits } from './cpf'

const PAGE_SIZE = 20
export const cpfRule: Rule = (value) => (isValidCpf(value) ? null : { key: 'patients.cpfInvalid' })

export function PatientsPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { can } = useSession()
  const { check } = useFormErrors()
  const [name, setName] = useState('')
  const [appliedName, setAppliedName] = useState('')
  const [page, setPage] = useState(0)
  const { load } = useLoad(
    () => call(api.GET('/api/v1/patients', { params: { query: { page, size: PAGE_SIZE, ...(appliedName ? { name: appliedName } : {}) } } })),
    [api, page, appliedName],
  )
  const [cpf, setCpf] = useState('')
  const [cpfSubmitted, setCpfSubmitted] = useState(false)
  const search = useAction()
  const [found, setFound] = useState<{ id?: string; fullName?: string; cpf?: string }[] | null>(null)
  const cpfError = check(cpf, [required, cpfRule])

  async function searchByCpf(event: FormEvent) {
    event.preventDefault()
    setCpfSubmitted(true)
    if (cpfError) return
    // O CPF vai só no corpo do POST: nunca na URL (que pode ir para log, histórico e proxies).
    await search.run(async () => setFound((await call(api.POST('/api/v1/patients/search', { body: { cpf: onlyDigits(cpf) } })))?.items ?? []))
  }

  return (
    <section>
      <h1>{t('patients.title')}</h1>
      {can('MANAGE_PATIENTS') && <Link to="/patients/new">{t('patients.new')}</Link>}

      <form aria-label={t('patients.searchByCpf')} onSubmit={(e) => void searchByCpf(e)} noValidate>
        <TextField label={t('patients.cpf')} value={cpf} onChange={setCpf} error={cpfSubmitted ? cpfError : undefined} />
        <button type="submit" disabled={search.busy}>
          {t('patients.searchByCpf')}
        </button>
      </form>
      <ProblemAlert problem={search.problem} />
      {found && found.length === 0 && <p>{t('patients.notFound')}</p>}
      {found && found.length > 0 && (
        <ul aria-label={t('patients.cpfResult')}>
          {found.map((p) => (
            <li key={p.id}>
              <Link to={`/patients/${p.id}`}>{p.fullName}</Link> {formatCpf(p.cpf)}
            </li>
          ))}
        </ul>
      )}

      <form
        aria-label={t('patients.filterByName')}
        onSubmit={(e) => {
          e.preventDefault()
          setPage(0)
          setAppliedName(name.trim())
        }}
      >
        <TextField label={t('patients.name')} value={name} onChange={setName} />
        <button type="submit">{t('patients.filter')}</button>
      </form>
      {load.state === 'loading' && <p>{t('common.loading')}</p>}
      {load.state === 'error' && <ProblemAlert problem={load.problem} />}
      {load.state === 'ready' && (
        <>
          {(load.data?.items ?? []).length === 0 && <p>{t('patients.empty')}</p>}
          <ul>
            {(load.data?.items ?? []).map((p) => (
              <li key={p.id}>
                <Link to={`/patients/${p.id}`}>{p.fullName}</Link>
              </li>
            ))}
          </ul>
          <button disabled={page === 0} onClick={() => setPage((n) => n - 1)}>
            {t('common.previous')}
          </button>
          <button disabled={(page + 1) * PAGE_SIZE >= (load.data?.total ?? 0)} onClick={() => setPage((n) => n + 1)}>
            {t('common.next')}
          </button>
        </>
      )}
    </section>
  )
}

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { CheckboxField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { onlyDigits } from './cpf'
import { PatientForm, emptyPatient, toRequest } from './PatientForm'

export function PatientNewPage() {
  const api = useApi()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { load } = useLoad(async () => {
    const types = (await call(api.GET('/api/v1/consent-types', { params: { query: { active: true } } }))) ?? []
    return Promise.all(types.map(async (type) => ({ summary: type, detail: await call(api.GET('/api/v1/consent-types/{id}', { params: { path: { id: type.id! } } })) })))
  }, [api])
  const { busy, problem, run } = useAction()
  const [accepted, setAccepted] = useState<Set<string>>(new Set())

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const types = load.data
  // Sem aceitar todos os consentimentos obrigatórios nada é enviado (RF5a); o servidor confere de novo.
  const missingRequired = types.filter((x) => x.summary.requiredOnSignup && !accepted.has(x.summary.id ?? ''))

  return (
    <PatientForm
      title={t('patients.new')}
      submitLabel={t('patients.register')}
      initial={emptyPatient}
      cpfEditable
      blocked={missingRequired.length > 0}
      busy={busy}
      problem={problem}
      onSubmit={(values) =>
        run(async () => {
          const created = await call(
            api.POST('/api/v1/patients', { body: { cpf: onlyDigits(values.cpf), ...toRequest(values), consents: [...accepted].map((typeId) => ({ typeId })) } }),
          )
          navigate(`/patients/${created?.id}`)
        })
      }
      extra={({ submitted }) => (
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
      )}
    />
  )
}

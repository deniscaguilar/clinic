import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { useSession } from '../session/SessionProvider'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { onlyDigits } from './cpf'
import { PatientForm, toRequest, toValues } from './PatientForm'

export function PatientEditPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { can } = useSession()
  const navigate = useNavigate()
  const id = useParams().id ?? ''
  const { load } = useLoad(() => call(api.GET('/api/v1/patients/{id}', { params: { path: { id } } })), [api, id])
  const { busy, problem, run } = useAction()

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const patient = load.data
  const initial = toValues(patient ?? {})
  const canChangeCpf = can('CHANGE_PATIENT_CPF')

  return (
    <PatientForm
      title={t('patients.edit')}
      submitLabel={t('patients.saveChanges')}
      initial={initial}
      cpfEditable={canChangeCpf}
      busy={busy}
      problem={problem}
      onSubmit={(values) =>
        run(async () => {
          // O CPF só vai quando a pessoa pode trocá-lo e ele mudou; o servidor confere a permissão de novo.
          const changed = canChangeCpf && onlyDigits(values.cpf) !== onlyDigits(initial.cpf)
          await call(api.PUT('/api/v1/patients/{id}', { params: { path: { id } }, body: { cpf: changed ? onlyDigits(values.cpf) : null, ...toRequest(values) } }))
          navigate(`/patients/${id}`)
        })
      }
    />
  )
}

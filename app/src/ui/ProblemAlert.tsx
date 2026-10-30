import { useTranslation } from 'react-i18next'
import { errorText, type Problem } from '../api/errors'

export function ProblemAlert({ problem }: { problem: Problem | null }) {
  const { t, i18n } = useTranslation()
  if (!problem) return null
  return <p role="alert">{errorText(t, (key) => i18n.exists(key), problem)}</p>
}

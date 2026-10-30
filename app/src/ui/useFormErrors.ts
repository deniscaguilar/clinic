import { useTranslation } from 'react-i18next'
import type { Problem } from '../api/errors'
import { firstError, type Rule } from './validation'

/** Mensagem de cada campo: a validação do navegador (regras) ou o que o servidor devolveu em `errors`. */
export function useFormErrors() {
  const { t, i18n } = useTranslation()
  const ruleMessage = (error: ReturnType<typeof firstError>) => (error ? t(error.key, error.options) : undefined)
  const serverMessage = (problem: Problem | null, field: string) => {
    const issue = problem?.code === 'VALIDATION_FAILED' ? problem.fields.find((f) => f.field === field) : undefined
    if (!issue) return undefined
    const key = `validation.rules.${issue.rule}`
    return t(i18n.exists(key) ? key : 'validation.rules.invalid')
  }
  return { ruleMessage, serverMessage, check: (value: string, rules: Rule[]) => ruleMessage(firstError(value, rules)) }
}

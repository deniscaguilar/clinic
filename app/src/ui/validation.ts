/** Regras de formulário; devolvem a chave do texto de erro (ou `null` se estiver certo). */
export type Rule = (value: string) => { key: string; options?: Record<string, unknown> } | null

export const required: Rule = (value) => (value.trim() === '' ? { key: 'validation.required' } : null)

export const maxLength =
  (max: number): Rule =>
  (value) =>
    value.trim().length > max ? { key: 'validation.tooLong', options: { max } } : null

export const email: Rule = (value) => (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim()) ? null : { key: 'validation.email' })

export function firstError(value: string, rules: Rule[]) {
  for (const rule of rules) {
    const error = rule(value)
    if (error) return error
  }
  return null
}

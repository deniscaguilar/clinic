import i18next, { type i18n } from 'i18next'
import { ptBR } from './pt-BR'

export const defaultLanguage = 'pt-BR'

interface Options {
  /** Em modo estrito uma chave ausente lança erro (testes). Fora dele a tela mostra a própria chave, nunca uma exceção. */
  strict?: boolean
}

export function createI18n({ strict = false }: Options = {}): i18n {
  const instance = i18next.createInstance()
  void instance.init({
    lng: defaultLanguage,
    fallbackLng: false,
    resources: { [defaultLanguage]: { translation: ptBR } },
    interpolation: { escapeValue: false }, // o React já escapa
    initAsync: false,
    ...(strict
      ? {
          parseMissingKeyHandler: (key: string): string => {
            throw new Error(`Missing translation key: ${key}`)
          },
        }
      : {}),
  })
  return instance
}

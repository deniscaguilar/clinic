import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { render, screen } from '@testing-library/react'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import { I18nextProvider as I18nProvider, useTranslation } from 'react-i18next'
import { createI18n } from './index'
import { ptBR } from './pt-BR'

const srcDir = resolve(process.cwd(), 'src')

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return sourceFiles(path)
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) && !name.endsWith('.d.ts') && !path.includes('/test/') ? [path] : []
  })
}

function hasKey(resources: Record<string, unknown>, key: string): boolean {
  // chave de plural (`chave_one`/`chave_other`) vale como a chave base
  return hasExactKey(resources, key) || hasExactKey(resources, `${key}_other`)
}

function hasExactKey(resources: Record<string, unknown>, key: string): boolean {
  let node: unknown = resources
  for (const part of key.split('.')) {
    if (typeof node !== 'object' || node === null || !(part in node)) return false
    node = (node as Record<string, unknown>)[part]
  }
  return typeof node === 'string'
}

describe('i18n', () => {
  it('translates a key to pt-BR', () => {
    const i18n = createI18n({ strict: true })
    expect(i18n.t('app.name')).toBe('Sistema da Clínica')
  })

  it('fails on a missing key in strict mode', () => {
    const i18n = createI18n({ strict: true })
    expect(() => i18n.t('does.not.exist')).toThrow(/does.not.exist/)
  })

  it('returns the key itself outside strict mode, so a user never sees an exception', () => {
    expect(createI18n({ strict: false }).t('does.not.exist')).toBe('does.not.exist')
  })

  it('renders translated text through the React hook', () => {
    const i18n = createI18n({ strict: true })
    function Probe() {
      const { t } = useTranslation()
      return <p>{t('app.name')}</p>
    }
    render(<Probe />, { wrapper: ({ children }) => <I18nProvider i18n={i18n}>{children}</I18nProvider> })
    expect(screen.getByText('Sistema da Clínica')).toBeInTheDocument()
  })

  it('every literal key used in the source exists in the pt-BR texts', () => {
    const missing: string[] = []
    for (const file of sourceFiles(srcDir)) {
      const text = readFileSync(file, 'utf8')
      // `t('chave')` e `labelKey: 'chave'` (chaves guardadas em dados, como o menu)
      for (const match of text.matchAll(/(?:\bt\(\s*|\blabelKey:\s*)'([a-zA-Z0-9_.]+)'/g)) {
        if (!hasKey(ptBR, match[1]!)) missing.push(`${file.replace(srcDir, 'src')}: ${match[1]}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('has no hardcoded user-facing text in the components', () => {
    const offenders: string[] = []
    const textAttributes = new Set(['title', 'placeholder', 'aria-label', 'alt', 'label'])
    for (const file of sourceFiles(srcDir)) {
      if (!file.endsWith('.tsx')) continue
      const source = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
      const visit = (node: ts.Node) => {
        if (ts.isJsxText(node) && /\p{L}/u.test(node.text)) offenders.push(`${file.replace(srcDir, 'src')}: "${node.text.trim()}"`)
        if (ts.isJsxAttribute(node) && textAttributes.has(node.name.getText()) && node.initializer && ts.isStringLiteral(node.initializer)) {
          offenders.push(`${file.replace(srcDir, 'src')}: ${node.name.getText()}="${node.initializer.text}"`)
        }
        ts.forEachChild(node, visit)
      }
      visit(source)
    }
    expect(offenders).toEqual([])
  })
})

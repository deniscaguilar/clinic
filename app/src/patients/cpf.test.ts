import { describe, expect, it } from 'vitest'
import { formatCpf, isValidCpf, onlyDigits } from './cpf'

describe('isValidCpf', () => {
  it.each(['529.982.247-25', '52998224725', '111.444.777-35'])('accepts %s', (cpf) => {
    expect(isValidCpf(cpf)).toBe(true)
  })

  it.each([
    ['wrong first check digit', '529.982.247-35'],
    ['wrong second check digit', '529.982.247-26'],
    ['repeated digits', '111.111.111-11'],
    ['too short', '5299822472'],
    ['too long', '529982247250'],
    ['letters', 'abcdefghijk'],
    ['empty', ''],
  ])('rejects %s', (_, cpf) => {
    expect(isValidCpf(cpf)).toBe(false)
  })

  it('treats a check digit of 10 as 0', () => {
    expect(isValidCpf('000.000.001-91')).toBe(true)
  })
})

describe('formatting', () => {
  it('keeps only digits', () => {
    expect(onlyDigits('529.982.247-25')).toBe('52998224725')
  })

  it('formats 11 digits and leaves anything else as it came', () => {
    expect(formatCpf('52998224725')).toBe('529.982.247-25')
    expect(formatCpf('123')).toBe('123')
    expect(formatCpf(undefined)).toBe('')
  })
})

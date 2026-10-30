import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { calls, clinicFetch } from '../test/clinic'
import { bodyOf, jsonResponse, problem } from '../test/fakes'
import { renderApp } from '../test/render'

beforeEach(() => window.sessionStorage.clear())

const click = userEvent.click
const type = userEvent.type
const CPF = '529.982.247-25'
const P = 'pppppppp-0000-0000-0000-000000000001'
const patient = { id: P, fullName: 'Maria Souza', cpf: '52998224725', birthDate: '1980-05-17', email: 'maria@x.test', whatsapp: '11999990000', address: {} }
const page = { items: [{ id: P, fullName: 'Maria Souza', cpf: '***', birthDate: '1980-05-17' }], page: 0, size: 20, total: 1 }
const MANAGE = ['VIEW_PATIENTS', 'MANAGE_PATIENTS', 'RECORD_CONSENT']

describe('patient list and CPF search', () => {
  it('lists patients linking by id only', async () => {
    renderApp(clinicFetch({ '/api/v1/patients': page }, MANAGE), '/patients')
    expect(await screen.findByRole('link', { name: 'Maria Souza' })).toHaveAttribute('href', `/patients/${P}`)
  })

  it('shows the register link only with MANAGE_PATIENTS', async () => {
    renderApp(clinicFetch({ '/api/v1/patients': page }, ['VIEW_PATIENTS']), '/patients')
    await screen.findByRole('link', { name: 'Maria Souza' })
    expect(screen.queryByRole('link', { name: 'Novo paciente' })).not.toBeInTheDocument()
  })

  it('filters by name and goes back to the first page', async () => {
    const fetch = clinicFetch({ '/api/v1/patients': page }, MANAGE)
    renderApp(fetch, '/patients')
    await screen.findByRole('link', { name: 'Maria Souza' })
    await type(screen.getByLabelText('Nome'), 'Mar')
    await click(screen.getByRole('button', { name: 'Filtrar' }))
    await waitFor(() => expect(new URL(fetch.calls.at(-1)!.url).searchParams.get('name')).toBe('Mar'))
    expect(new URL(fetch.calls.at(-1)!.url).searchParams.get('page')).toBe('0')
  })

  it('rejects an invalid CPF without calling the API', async () => {
    const fetch = clinicFetch({ '/api/v1/patients': page }, MANAGE)
    renderApp(fetch, '/patients')
    await screen.findByRole('link', { name: 'Maria Souza' })
    await type(screen.getByLabelText('CPF'), '111.111.111-11')
    await click(screen.getByRole('button', { name: 'Buscar por CPF' }))
    expect(screen.getByText('CPF inválido.')).toBeInTheDocument()
    expect(calls(fetch, 'POST', '/api/v1/patients/search')).toHaveLength(0)
  })

  it('searches by CPF with the number only in the POST body, never in a URL', async () => {
    const fetch = clinicFetch({ '/api/v1/patients': page, 'POST /api/v1/patients/search': jsonResponse({ items: [patient] }) }, MANAGE)
    renderApp(fetch, '/patients')
    await screen.findByRole('link', { name: 'Maria Souza' })
    await type(screen.getByLabelText('CPF'), CPF)
    await click(screen.getByRole('button', { name: 'Buscar por CPF' }))
    const result = within(await screen.findByRole('list', { name: 'Resultado da busca por CPF' }))
    expect(result.getByRole('link', { name: 'Maria Souza' })).toHaveAttribute('href', `/patients/${P}`)
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/patients/search')[0]!)).toEqual({ cpf: '52998224725' })
    for (const request of fetch.calls) expect(request.url).not.toMatch(/52998224725|529\.982/)
    for (const link of screen.getAllByRole('link')) expect(link.getAttribute('href')).not.toMatch(/52998224725|529\.982/)
  })

  it('says when no patient has the CPF', async () => {
    renderApp(clinicFetch({ '/api/v1/patients': page, 'POST /api/v1/patients/search': jsonResponse({ items: [] }) }, MANAGE), '/patients')
    await screen.findByRole('link', { name: 'Maria Souza' })
    await type(screen.getByLabelText('CPF'), CPF)
    await click(screen.getByRole('button', { name: 'Buscar por CPF' }))
    expect(await screen.findByText('Nenhum paciente com este CPF.')).toBeInTheDocument()
  })

  it('shows PERMISSION_DENIED on the list', async () => {
    renderApp(clinicFetch({ '/api/v1/patients': () => problem('PERMISSION_DENIED', 403) }, MANAGE), '/patients')
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para esta ação.')
  })
})

describe('patient registration', () => {
  const types = [
    { id: 'c1', name: 'LGPD', requiredOnSignup: true, controlsMessaging: false, active: true, currentVersion: { number: 1 } },
    { id: 'c2', name: 'Imagens', requiredOnSignup: false, controlsMessaging: false, active: true, currentVersion: { number: 1 } },
  ]
  const base = {
    '/api/v1/consent-types': types,
    '/api/v1/consent-types/c1': { ...types[0], currentVersion: { number: 1, text: 'Texto da LGPD' }, versions: [] },
    '/api/v1/consent-types/c2': { ...types[1], currentVersion: { number: 1, text: 'Texto das imagens' }, versions: [] },
    [`/api/v1/patients/${P}`]: patient,
    [`/api/v1/patients/${P}/consents`]: [],
  }

  async function fillValid() {
    await type(screen.getByLabelText('CPF'), CPF)
    await type(screen.getByLabelText('Nome completo'), '  Maria Souza ')
    await type(screen.getByLabelText('Data de nascimento'), '1980-05-17')
    await type(screen.getByLabelText('WhatsApp'), '11999990000')
    await type(screen.getByLabelText('CEP'), '01310100')
    await type(screen.getByLabelText('Rua'), 'Av. Paulista')
    await type(screen.getByLabelText('Número'), '1000')
    await type(screen.getByLabelText('Bairro'), 'Bela Vista')
    await type(screen.getByLabelText('Cidade'), 'São Paulo')
    await type(screen.getByLabelText('UF'), 'sp')
  }

  it('shows each active consent with its text and marks the required one', async () => {
    renderApp(clinicFetch(base, MANAGE), '/patients/new')
    expect(await screen.findByLabelText('LGPD (obrigatório)')).not.toBeChecked()
    expect(screen.getByLabelText('Imagens')).toBeInTheDocument()
    expect(screen.getByText('Texto da LGPD')).toBeInTheDocument()
  })

  it('blocks the registration while a required consent is not accepted', async () => {
    const fetch = clinicFetch(base, MANAGE)
    renderApp(fetch, '/patients/new')
    await screen.findByLabelText('LGPD (obrigatório)')
    await fillValid()
    await click(screen.getByLabelText('Imagens'))
    await click(screen.getByRole('button', { name: 'Cadastrar paciente' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Aceite todos os consentimentos obrigatórios para continuar.')
    expect(calls(fetch, 'POST', '/api/v1/patients')).toHaveLength(0)
  })

  it('validates the CPF, the required fields and the e-mail before sending', async () => {
    const fetch = clinicFetch(base, MANAGE)
    renderApp(fetch, '/patients/new')
    await screen.findByLabelText('LGPD (obrigatório)')
    await type(screen.getByLabelText('CPF'), '123')
    await type(screen.getByLabelText('E-mail'), 'ruim')
    await click(screen.getByRole('button', { name: 'Cadastrar paciente' }))
    expect(screen.getByText('CPF inválido.')).toBeInTheDocument()
    expect(screen.getByText('E-mail inválido.')).toBeInTheDocument()
    expect(screen.getAllByText('Campo obrigatório.').length).toBeGreaterThanOrEqual(8)
    expect(calls(fetch, 'POST', '/api/v1/patients')).toHaveLength(0)
  })

  it('registers with the accepted consents and opens the patient', async () => {
    const fetch = clinicFetch({ ...base, 'POST /api/v1/patients': jsonResponse({ ...patient, consents: [] }, 201) }, MANAGE)
    renderApp(fetch, '/patients/new')
    await screen.findByLabelText('LGPD (obrigatório)')
    await fillValid()
    await click(screen.getByLabelText('LGPD (obrigatório)'))
    await click(screen.getByLabelText('Imagens'))
    await click(screen.getByRole('button', { name: 'Cadastrar paciente' }))
    expect(await screen.findByRole('heading', { name: 'Maria Souza' })).toBeInTheDocument()
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/patients')[0]!)).toEqual({
      cpf: '52998224725',
      fullName: 'Maria Souza',
      birthDate: '1980-05-17',
      whatsapp: '11999990000',
      email: null,
      landline: null,
      address: { zipCode: '01310100', street: 'Av. Paulista', number: '1000', complement: null, district: 'Bela Vista', city: 'São Paulo', state: 'SP' },
      consents: [{ typeId: 'c1' }, { typeId: 'c2' }],
    })
    for (const request of fetch.calls) expect(request.url).not.toMatch(/52998224725/)
  })

  it('shows CPF_ALREADY_REGISTERED from the server', async () => {
    renderApp(clinicFetch({ ...base, 'POST /api/v1/patients': () => problem('CPF_ALREADY_REGISTERED', 409) }, MANAGE), '/patients/new')
    await screen.findByLabelText('LGPD (obrigatório)')
    await fillValid()
    await click(screen.getByLabelText('LGPD (obrigatório)'))
    await click(screen.getByRole('button', { name: 'Cadastrar paciente' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe um paciente com este CPF.')
  })

  it('shows the server consent rule when it still refuses', async () => {
    renderApp(clinicFetch({ ...base, 'POST /api/v1/patients': () => problem('CONSENT_REQUIRED', 422) }, MANAGE), '/patients/new')
    await screen.findByLabelText('LGPD (obrigatório)')
    await fillValid()
    await click(screen.getByLabelText('LGPD (obrigatório)'))
    await click(screen.getByRole('button', { name: 'Cadastrar paciente' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Aceite todos os consentimentos obrigatórios')
  })
})

describe('patient consents', () => {
  const grants = [
    { id: 'g1', typeId: 'c1', typeName: 'LGPD', versionNumber: 2, acceptedAt: '2026-10-30T15:00:00Z', active: true, revokedAt: null },
    { id: 'g2', typeId: 'c3', typeName: 'Mensagens', versionNumber: 1, acceptedAt: '2026-10-01T12:00:00Z', active: false, revokedAt: '2026-10-20T12:00:00Z' },
  ]
  const types = [
    { id: 'c1', name: 'LGPD', requiredOnSignup: true, active: true },
    { id: 'c2', name: 'Imagens', requiredOnSignup: false, active: true },
    { id: 'c3', name: 'Mensagens', requiredOnSignup: false, active: true },
  ]
  const base = { [`/api/v1/patients/${P}`]: patient, [`/api/v1/patients/${P}/consents`]: grants, '/api/v1/consent-types': types }

  it('shows the grants in the clinic time zone, active and revoked', async () => {
    renderApp(clinicFetch(base, MANAGE), `/patients/${P}`)
    const list = within(await screen.findByRole('list', { name: 'Consentimentos' }))
    expect(list.getByText(/LGPD · Versão 2 · aceito em 30\/10\/2026.*12:00/)).toBeInTheDocument()
    expect(list.getByText(/revogado em 20\/10\/2026/)).toBeInTheDocument()
    expect(list.getAllByRole('button', { name: 'Revogar' })).toHaveLength(1)
  })

  it('offers to record only the types without an active grant (a revoked one can be accepted again)', async () => {
    renderApp(clinicFetch(base, MANAGE), `/patients/${P}`)
    await screen.findByRole('list', { name: 'Consentimentos' })
    const offers = screen.getByRole('heading', { name: 'Registrar consentimento' }).nextElementSibling as HTMLElement
    expect(within(offers).getByText('Imagens')).toBeInTheDocument()
    expect(within(offers).getByText('Mensagens')).toBeInTheDocument()
    expect(within(offers).queryByText('LGPD')).not.toBeInTheDocument()
  })

  it('accepts a consent', async () => {
    const fetch = clinicFetch({ ...base, [`POST /api/v1/patients/${P}/consents`]: jsonResponse(grants[0], 201) }, MANAGE)
    renderApp(fetch, `/patients/${P}`)
    const imagens = (await screen.findByText('Imagens')).closest('li')!
    await click(within(imagens).getByRole('button', { name: 'Registrar aceite' }))
    await waitFor(() => expect(calls(fetch, 'POST', `/api/v1/patients/${P}/consents`)).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'POST', `/api/v1/patients/${P}/consents`)[0]!)).toEqual({ typeId: 'c2' })
  })

  it('revokes a consent', async () => {
    const fetch = clinicFetch({ ...base, [`POST /api/v1/patients/${P}/consents/g1/revoke`]: jsonResponse({ ...grants[0], active: false }) }, MANAGE)
    renderApp(fetch, `/patients/${P}`)
    await click(await screen.findByRole('button', { name: 'Revogar' }))
    await waitFor(() => expect(calls(fetch, 'POST', `/api/v1/patients/${P}/consents/g1/revoke`)).toHaveLength(1))
  })

  it('hides accept and revoke without RECORD_CONSENT', async () => {
    renderApp(clinicFetch(base, ['VIEW_PATIENTS']), `/patients/${P}`)
    await screen.findByRole('list', { name: 'Consentimentos' })
    expect(screen.queryByRole('button', { name: 'Revogar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar aceite' })).not.toBeInTheDocument()
  })

  it('shows CONSENT_ALREADY_REVOKED', async () => {
    renderApp(clinicFetch({ ...base, [`POST /api/v1/patients/${P}/consents/g1/revoke`]: () => problem('CONSENT_ALREADY_REVOKED', 409) }, MANAGE), `/patients/${P}`)
    await click(await screen.findByRole('button', { name: 'Revogar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Este consentimento já foi revogado.')
  })

  it('shows PATIENT_NOT_FOUND', async () => {
    renderApp(clinicFetch({ [`/api/v1/patients/${P}`]: () => problem('PATIENT_NOT_FOUND', 404) }, MANAGE), `/patients/${P}`)
    expect(await screen.findByRole('alert')).toHaveTextContent('Paciente não encontrado.')
  })
})

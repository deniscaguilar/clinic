import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { calls, clinicFetch } from '../test/clinic'
import { bodyOf, jsonResponse, problem } from '../test/fakes'
import { renderApp } from '../test/render'

beforeEach(() => window.sessionStorage.clear())

const click = userEvent.click
const type = userEvent.type
const P = 'pppppppp-0000-0000-0000-000000000001'
const patient = {
  id: P,
  fullName: 'Maria Souza',
  cpf: '52998224725',
  birthDate: '1980-05-17',
  whatsapp: '11999990000',
  email: 'maria@x.test',
  landline: null,
  address: { zipCode: '01310100', street: 'Av. Paulista', number: '1000', complement: null, district: 'Bela Vista', city: 'São Paulo', state: 'SP' },
}
const EDIT = ['VIEW_PATIENTS', 'MANAGE_PATIENTS']
const base = { [`/api/v1/patients/${P}`]: patient, [`/api/v1/patients/${P}/consents`]: [], '/api/v1/consent-types': [] }
const putCalls = (fetch: ReturnType<typeof clinicFetch>) => calls(fetch, 'PUT', `/api/v1/patients/${P}`)

describe('patient edit', () => {
  it('offers the edit link only with MANAGE_PATIENTS', async () => {
    renderApp(clinicFetch(base, EDIT), `/patients/${P}`)
    expect(await screen.findByRole('link', { name: 'Editar paciente' })).toHaveAttribute('href', `/patients/${P}/edit`)
  })

  it('hides the edit link for a viewer', async () => {
    renderApp(clinicFetch(base, ['VIEW_PATIENTS']), `/patients/${P}`)
    await screen.findByRole('heading', { name: 'Maria Souza' })
    expect(screen.queryByRole('link', { name: 'Editar paciente' })).not.toBeInTheDocument()
  })

  it('loads the current data into the form', async () => {
    renderApp(clinicFetch(base, EDIT), `/patients/${P}/edit`)
    expect(await screen.findByDisplayValue('Maria Souza')).toBeInTheDocument()
    expect(screen.getByLabelText('Data de nascimento')).toHaveValue('1980-05-17')
    expect(screen.getByLabelText('WhatsApp')).toHaveValue('11999990000')
    expect(screen.getByLabelText('Rua')).toHaveValue('Av. Paulista')
    expect(screen.getByLabelText('UF')).toHaveValue('SP')
  })

  it('shows the CPF read-only and does not send it without CHANGE_PATIENT_CPF', async () => {
    const fetch = clinicFetch({ ...base, [`PUT /api/v1/patients/${P}`]: jsonResponse(patient) }, EDIT)
    renderApp(fetch, `/patients/${P}/edit`)
    const cpf = await screen.findByLabelText('CPF')
    expect(cpf).toHaveValue('529.982.247-25')
    expect(cpf).toHaveAttribute('readonly')
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    await waitFor(() => expect(putCalls(fetch)).toHaveLength(1))
    expect(((await bodyOf(putCalls(fetch)[0]!)) as { cpf: unknown }).cpf).toBeNull()
  })

  it('saves the changes and returns to the patient', async () => {
    const fetch = clinicFetch({ ...base, [`PUT /api/v1/patients/${P}`]: jsonResponse(patient) }, EDIT)
    renderApp(fetch, `/patients/${P}/edit`)
    const name = await screen.findByDisplayValue('Maria Souza')
    await userEvent.clear(name)
    await type(name, '  Maria Souza Lima ')
    await userEvent.clear(screen.getByLabelText('E-mail'))
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    expect(await screen.findByRole('heading', { name: 'Maria Souza' })).toBeInTheDocument()
    expect(await bodyOf(putCalls(fetch)[0]!)).toEqual({
      cpf: null,
      fullName: 'Maria Souza Lima',
      birthDate: '1980-05-17',
      whatsapp: '11999990000',
      email: null,
      landline: null,
      address: { zipCode: '01310100', street: 'Av. Paulista', number: '1000', complement: null, district: 'Bela Vista', city: 'São Paulo', state: 'SP' },
    })
  })

  it('does not call the API with a blank name', async () => {
    const fetch = clinicFetch(base, EDIT)
    renderApp(fetch, `/patients/${P}/edit`)
    await userEvent.clear(await screen.findByDisplayValue('Maria Souza'))
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    expect(screen.getByText('Campo obrigatório.')).toBeInTheDocument()
    expect(putCalls(fetch)).toHaveLength(0)
  })

  it('lets one change the CPF with CHANGE_PATIENT_CPF, validating it', async () => {
    const fetch = clinicFetch({ ...base, [`PUT /api/v1/patients/${P}`]: jsonResponse(patient) }, [...EDIT, 'CHANGE_PATIENT_CPF'])
    renderApp(fetch, `/patients/${P}/edit`)
    const cpf = await screen.findByLabelText('CPF')
    expect(cpf).not.toHaveAttribute('readonly')
    await userEvent.clear(cpf)
    await type(cpf, '111.111.111-11')
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    expect(screen.getByText('CPF inválido.')).toBeInTheDocument()
    expect(putCalls(fetch)).toHaveLength(0)
    await userEvent.clear(cpf)
    await type(cpf, '111.444.777-35')
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    await waitFor(() => expect(putCalls(fetch)).toHaveLength(1))
    expect(((await bodyOf(putCalls(fetch)[0]!)) as { cpf: unknown }).cpf).toBe('11144477735')
    for (const request of fetch.calls) expect(request.url).not.toMatch(/11144477735|52998224725/)
  })

  it('sends no CPF when it was not changed even with CHANGE_PATIENT_CPF', async () => {
    const fetch = clinicFetch({ ...base, [`PUT /api/v1/patients/${P}`]: jsonResponse(patient) }, [...EDIT, 'CHANGE_PATIENT_CPF'])
    renderApp(fetch, `/patients/${P}/edit`)
    await screen.findByDisplayValue('Maria Souza')
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    await waitFor(() => expect(putCalls(fetch)).toHaveLength(1))
    expect(((await bodyOf(putCalls(fetch)[0]!)) as { cpf: unknown }).cpf).toBeNull()
  })

  it('shows CPF_ALREADY_REGISTERED from the server', async () => {
    renderApp(clinicFetch({ ...base, [`PUT /api/v1/patients/${P}`]: () => problem('CPF_ALREADY_REGISTERED', 409) }, [...EDIT, 'CHANGE_PATIENT_CPF']), `/patients/${P}/edit`)
    const cpf = await screen.findByLabelText('CPF')
    await userEvent.clear(cpf)
    await type(cpf, '111.444.777-35')
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe um paciente com este CPF.')
  })

  it('shows PERMISSION_DENIED when the server refuses the CPF change', async () => {
    renderApp(clinicFetch({ ...base, [`PUT /api/v1/patients/${P}`]: () => problem('PERMISSION_DENIED', 403) }, EDIT), `/patients/${P}/edit`)
    await screen.findByDisplayValue('Maria Souza')
    await click(screen.getByRole('button', { name: 'Salvar alterações' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para esta ação.')
  })

  it('shows PATIENT_NOT_FOUND on load', async () => {
    renderApp(clinicFetch({ [`/api/v1/patients/${P}`]: () => problem('PATIENT_NOT_FOUND', 404) }, EDIT), `/patients/${P}/edit`)
    expect(await screen.findByRole('alert')).toHaveTextContent('Paciente não encontrado.')
  })
})

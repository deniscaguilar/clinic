import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { calls, clinicFetch } from '../test/clinic'
import { bodyOf, jsonResponse, problem } from '../test/fakes'
import { renderApp } from '../test/render'

beforeEach(() => {
  window.sessionStorage.clear()
  document.documentElement.removeAttribute('style')
})

const click = userEvent.click
const type = userEvent.type

describe('roles', () => {
  const roles = [{ id: 'r1', name: 'Recepção', permissions: ['VIEW_PATIENTS'], members: 2 }]
  const permissions = [
    { code: 'VIEW_PATIENTS', description: 'Ver pacientes' },
    { code: 'MANAGE_USERS', description: 'Gerir usuários' },
  ]

  it('lists the roles with the number of members', async () => {
    renderApp(clinicFetch({ '/api/v1/roles': roles, '/api/v1/permissions': permissions }), '/roles')
    expect(await screen.findByText('Recepção')).toBeInTheDocument()
    expect(screen.getByText('2 usuários')).toBeInTheDocument()
  })

  it('does not save a role without a name', async () => {
    const fetch = clinicFetch({ '/api/v1/roles': roles, '/api/v1/permissions': permissions })
    renderApp(fetch, '/roles')
    await click(await screen.findByRole('button', { name: 'Novo perfil' }))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Campo obrigatório.')).toBeInTheDocument()
    expect(calls(fetch, 'POST', '/api/v1/roles')).toHaveLength(0)
  })

  it('creates a role with the chosen permissions', async () => {
    const fetch = clinicFetch({ '/api/v1/roles': roles, '/api/v1/permissions': permissions, 'POST /api/v1/roles': jsonResponse({ id: 'r2' }, 201) })
    renderApp(fetch, '/roles')
    await click(await screen.findByRole('button', { name: 'Novo perfil' }))
    await type(screen.getByLabelText('Nome do perfil'), 'Gestão')
    await click(screen.getByLabelText('Gerir usuários'))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'POST', '/api/v1/roles')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/roles')[0]!)).toEqual({ name: 'Gestão', permissions: ['MANAGE_USERS'] })
  })

  it('edits a role loading its permissions', async () => {
    const fetch = clinicFetch({ '/api/v1/roles': roles, '/api/v1/permissions': permissions, 'PUT /api/v1/roles/r1': jsonResponse(roles[0]) })
    renderApp(fetch, '/roles')
    await click(await screen.findByRole('button', { name: 'Editar' }))
    expect(screen.getByLabelText('Ver pacientes')).toBeChecked()
    expect(screen.getByLabelText('Gerir usuários')).not.toBeChecked()
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'PUT', '/api/v1/roles/r1')).toHaveLength(1))
  })

  it('shows ROLE_IN_USE when the role cannot be removed', async () => {
    renderApp(clinicFetch({ '/api/v1/roles': roles, '/api/v1/permissions': permissions, 'DELETE /api/v1/roles/r1': () => problem('ROLE_IN_USE', 409) }), '/roles')
    await click(await screen.findByRole('button', { name: 'Remover' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('O perfil está em uso e não pode ser removido.')
  })
})

describe('users', () => {
  const members = [
    { membershipId: 'm1', userId: 'u1', email: 'ana@x.test', name: 'Ana', status: 'PENDING', roles: [{ id: 'r1', name: 'Recepção' }] },
    { membershipId: 'm2', userId: 'u2', email: 'bia@x.test', name: 'Bia', status: 'ACTIVE', roles: [] },
    { membershipId: 'm3', userId: 'u3', email: 'cris@x.test', name: 'Cris', status: 'INACTIVE', roles: [] },
  ]
  const roles = [{ id: 'r1', name: 'Recepção', permissions: [], members: 1 }]
  const base = { '/api/v1/users': members, '/api/v1/roles': roles }

  it('shows each member with the role they have', async () => {
    renderApp(clinicFetch(base), '/users')
    const ana = (await screen.findByText('Ana')).closest('li')!
    expect(within(ana).getByLabelText('Recepção')).toBeChecked()
    expect(within(ana).getByText('Convite pendente')).toBeInTheDocument()
  })

  it('offers resend only to pending and deactivate only to non inactive members', async () => {
    renderApp(clinicFetch(base), '/users')
    const item = async (name: string) => (await screen.findByText(name)).closest('li')!
    expect(within(await item('Ana')).getByRole('button', { name: 'Reenviar convite' })).toBeInTheDocument()
    expect(within(await item('Bia')).queryByRole('button', { name: 'Reenviar convite' })).not.toBeInTheDocument()
    expect(within(await item('Cris')).queryByRole('button', { name: 'Desativar usuário' })).not.toBeInTheDocument()
    expect(within(await item('Bia')).getByRole('button', { name: 'Desativar usuário' })).toBeInTheDocument()
  })

  it('validates the invitation before sending it', async () => {
    const fetch = clinicFetch(base)
    renderApp(fetch, '/users')
    await click(await screen.findByRole('button', { name: 'Enviar convite' }))
    expect(screen.getAllByText('Campo obrigatório.')).toHaveLength(2)
    await type(screen.getByLabelText('E-mail'), 'invalido')
    await click(screen.getByRole('button', { name: 'Enviar convite' }))
    expect(screen.getByText('E-mail inválido.')).toBeInTheDocument()
    expect(calls(fetch, 'POST', '/api/v1/users')).toHaveLength(0)
  })

  it('invites a user with the chosen roles', async () => {
    const fetch = clinicFetch({ ...base, 'POST /api/v1/users': jsonResponse({ membershipId: 'm9', status: 'PENDING' }, 201) })
    renderApp(fetch, '/users')
    const form = within(await screen.findByRole('form', { name: 'Convidar usuário' }))
    await type(form.getByLabelText('E-mail'), 'novo@x.test')
    await type(form.getByLabelText('Nome'), 'Novo')
    await click(form.getByLabelText('Recepção'))
    await click(form.getByRole('button', { name: 'Enviar convite' }))
    await waitFor(() => expect(calls(fetch, 'POST', '/api/v1/users')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/users')[0]!)).toEqual({ email: 'novo@x.test', name: 'Novo', roleIds: ['r1'] })
  })

  it('saves the roles of a member', async () => {
    const fetch = clinicFetch({ ...base, 'PATCH /api/v1/users/m2': new Response(null, { status: 204 }) })
    renderApp(fetch, '/users')
    const bia = (await screen.findByText('Bia')).closest('li')!
    await click(within(bia).getByLabelText('Recepção'))
    await click(within(bia).getByRole('button', { name: 'Salvar perfis' }))
    await waitFor(() => expect(calls(fetch, 'PATCH', '/api/v1/users/m2')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'PATCH', '/api/v1/users/m2')[0]!)).toEqual({ roleIds: ['r1'] })
  })

  it('shows LAST_CLINIC_ADMIN when the last administrator cannot be deactivated', async () => {
    renderApp(clinicFetch({ ...base, 'POST /api/v1/users/m2/deactivate': () => problem('LAST_CLINIC_ADMIN', 409) }), '/users')
    const bia = (await screen.findByText('Bia')).closest('li')!
    await click(within(bia).getByRole('button', { name: 'Desativar usuário' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('A clínica precisa de pelo menos um administrador.')
  })
})

describe('procedures', () => {
  const list = [{ id: 'p1', name: 'Endoscopia', description: 'Exame', active: true }, { id: 'p2', name: 'Antigo', description: null, active: false }]

  it('lists procedures and marks the inactive ones', async () => {
    renderApp(clinicFetch({ '/api/v1/procedures': list }), '/procedures')
    expect(await screen.findByText('Endoscopia')).toBeInTheDocument()
    expect(screen.getByText('(inativo)')).toBeInTheDocument()
  })

  it('validates name and description size', async () => {
    const fetch = clinicFetch({ '/api/v1/procedures': list })
    renderApp(fetch, '/procedures')
    await click(await screen.findByRole('button', { name: 'Novo procedimento' }))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Campo obrigatório.')).toBeInTheDocument()
    await type(screen.getByLabelText('Nome do procedimento'), 'X')
    await click(screen.getByLabelText('Descrição'))
    await userEvent.paste('d'.repeat(501))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Texto longo demais (máximo de 500 caracteres).')).toBeInTheDocument()
    expect(calls(fetch, 'POST', '/api/v1/procedures')).toHaveLength(0)
  })

  it('creates a procedure with a null description when left blank', async () => {
    const fetch = clinicFetch({ '/api/v1/procedures': list, 'POST /api/v1/procedures': jsonResponse(list[0], 201) })
    renderApp(fetch, '/procedures')
    await click(await screen.findByRole('button', { name: 'Novo procedimento' }))
    await type(screen.getByLabelText('Nome do procedimento'), '  Colonoscopia ')
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'POST', '/api/v1/procedures')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/procedures')[0]!)).toEqual({ name: 'Colonoscopia', description: null })
  })

  it('updates a procedure including the active flag', async () => {
    const fetch = clinicFetch({ '/api/v1/procedures': list, 'PUT /api/v1/procedures/p1': jsonResponse(list[0]) })
    renderApp(fetch, '/procedures')
    const row = (await screen.findByText('Endoscopia')).closest('li')!
    await click(within(row).getByRole('button', { name: 'Editar' }))
    await click(screen.getByLabelText('Ativo'))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'PUT', '/api/v1/procedures/p1')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'PUT', '/api/v1/procedures/p1')[0]!)).toEqual({ name: 'Endoscopia', description: 'Exame', active: false })
  })

  it('shows PROCEDURE_NAME_TAKEN', async () => {
    renderApp(clinicFetch({ '/api/v1/procedures': list, 'POST /api/v1/procedures': () => problem('PROCEDURE_NAME_TAKEN', 409) }), '/procedures')
    await click(await screen.findByRole('button', { name: 'Novo procedimento' }))
    await type(screen.getByLabelText('Nome do procedimento'), 'Endoscopia')
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe um procedimento com este nome.')
  })
})

describe('practitioners', () => {
  const members = [{ membershipId: 'm1', userId: 'u1', name: 'Dra. Ana', email: 'a@x.test', status: 'ACTIVE', roles: [] }, { membershipId: 'm2', userId: 'u2', name: 'Pendente', email: 'p@x.test', status: 'PENDING', roles: [] }]
  const list = [{ id: 'd1', name: 'Dr. Paulo', type: 'DOCTOR', registrationNumber: 'CRM1', userId: null, active: true, workSchedule: [{ dayOfWeek: 'MONDAY', start: '08:00', end: '12:00' }] }]
  const base = { '/api/v1/practitioners': list, '/api/v1/users': members }

  it('lists practitioners with their type', async () => {
    renderApp(clinicFetch(base), '/practitioners')
    expect(await screen.findByText('Dr. Paulo', { exact: false })).toHaveTextContent('Médico')
  })

  it('links only active members of the clinic', async () => {
    renderApp(clinicFetch(base), '/practitioners')
    await click(await screen.findByRole('button', { name: 'Novo profissional' }))
    const select = screen.getByLabelText('Usuário vinculado')
    expect(within(select).getByRole('option', { name: 'Dra. Ana' })).toBeInTheDocument()
    expect(within(select).queryByRole('option', { name: 'Pendente' })).not.toBeInTheDocument()
  })

  it('creates a practitioner with schedule and linked user', async () => {
    const fetch = clinicFetch({ ...base, 'POST /api/v1/practitioners': jsonResponse(list[0], 201) })
    renderApp(fetch, '/practitioners')
    await click(await screen.findByRole('button', { name: 'Novo profissional' }))
    await type(screen.getByLabelText('Nome do profissional'), 'Dra. Ana')
    await userEvent.selectOptions(screen.getByLabelText('Usuário vinculado'), 'u1')
    await click(screen.getByRole('button', { name: 'Adicionar horário' }))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'POST', '/api/v1/practitioners')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/practitioners')[0]!)).toEqual({
      name: 'Dra. Ana',
      type: 'DOCTOR',
      registrationNumber: null,
      userId: 'u1',
      workSchedule: [{ dayOfWeek: 'MONDAY', start: '08:00', end: '12:00' }],
    })
  })

  it('loads the schedule of a practitioner for editing and lets one remove an interval', async () => {
    const fetch = clinicFetch({ ...base, 'PUT /api/v1/practitioners/d1': jsonResponse(list[0]) })
    renderApp(fetch, '/practitioners')
    await click(await screen.findByRole('button', { name: 'Editar' }))
    expect(screen.getByLabelText('Início')).toHaveValue('08:00')
    await click(screen.getByRole('button', { name: 'Remover' }))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'PUT', '/api/v1/practitioners/d1')).toHaveLength(1))
    expect(((await bodyOf(calls(fetch, 'PUT', '/api/v1/practitioners/d1')[0]!)) as { workSchedule: unknown[] }).workSchedule).toEqual([])
  })

  it('requires a name and shows PRACTITIONER_USER_ALREADY_LINKED', async () => {
    renderApp(clinicFetch({ ...base, 'POST /api/v1/practitioners': () => problem('PRACTITIONER_USER_ALREADY_LINKED', 409) }), '/practitioners')
    await click(await screen.findByRole('button', { name: 'Novo profissional' }))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Campo obrigatório.')).toBeInTheDocument()
    await type(screen.getByLabelText('Nome do profissional'), 'X')
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Este usuário já está vinculado a outro profissional.')
  })
})

describe('branding', () => {
  const branding = { displayName: 'Clínica Aurora', primaryColor: '#112233', secondaryColor: '#223344', onPrimary: '#FFFFFF', logoUrl: null, faviconUrl: null }

  it('loads the current identity', async () => {
    renderApp(clinicFetch({ '/api/v1/branding': branding }), '/branding')
    expect(await screen.findByDisplayValue('#112233')).toBeInTheDocument()
    expect(screen.getByText('Cor do texto sobre a cor principal: #FFFFFF')).toBeInTheDocument()
  })

  it('rejects a color that is not #RRGGBB before calling the API', async () => {
    const fetch = clinicFetch({ '/api/v1/branding': branding })
    renderApp(fetch, '/branding')
    const input = await screen.findByDisplayValue('#112233')
    await userEvent.clear(input)
    await type(input, 'azul')
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Use o formato #RRGGBB.')).toBeInTheDocument()
    expect(calls(fetch, 'PUT', '/api/v1/branding')).toHaveLength(0)
  })

  it('shows BRAND_CONTRAST_INSUFFICIENT from the server', async () => {
    renderApp(clinicFetch({ '/api/v1/branding': branding, 'PUT /api/v1/branding': () => problem('BRAND_CONTRAST_INSUFFICIENT', 422) }), '/branding')
    await screen.findByDisplayValue('#112233')
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Contraste insuficiente entre a cor principal e o texto sobre ela.')
  })

  it('saves and applies the new theme right away', async () => {
    let primary = '#112233'
    const fetch = clinicFetch({
      '/api/v1/branding': () => jsonResponse({ ...branding, primaryColor: primary }),
      'PUT /api/v1/branding': () => {
        primary = '#AA0000'
        return jsonResponse({ ...branding, primaryColor: primary })
      },
      '/api/v1/session': () => jsonResponse({ user: {}, clinic: { id: 'clinic-a', name: 'x' }, roles: [], permissions: [], branding: { ...branding, primaryColor: primary } }),
    })
    renderApp(fetch, '/branding')
    const input = await screen.findByDisplayValue('#112233')
    await userEvent.clear(input)
    await type(input, '#AA0000')
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Identidade visual salva.')).toBeInTheDocument()
    await waitFor(() => expect(document.documentElement.style.getPropertyValue('--color-primary')).toBe('#AA0000'))
    expect(await bodyOf(calls(fetch, 'PUT', '/api/v1/branding')[0]!)).toEqual({ displayName: 'Clínica Aurora', primaryColor: '#AA0000', secondaryColor: '#223344' })
  })

  it('uploads the logo as multipart and shows INVALID image errors by code', async () => {
    const fetch = clinicFetch({ '/api/v1/branding': branding, 'PUT /api/v1/branding/logo': () => problem('IMAGE_TYPE_UNSUPPORTED', 415) })
    renderApp(fetch, '/branding')
    await screen.findByDisplayValue('#112233')
    await userEvent.upload(screen.getByLabelText('Logo'), new File(['x'], 'logo.png', { type: 'image/png' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Tipo de imagem não suportado.')
    expect(calls(fetch, 'PUT', '/api/v1/branding/logo')[0]!.headers.get('Content-Type')).toMatch(/multipart\/form-data/)
  })
})

describe('audit', () => {
  const page = { items: [{ id: 'e1', occurredAt: '2026-10-30T15:00:00Z', action: 'READ_PATIENT', entityType: 'PATIENT', outcome: 'SUCCESS' }], page: 0, size: 20, total: 45 }

  it('lists events with the time in the clinic time zone', async () => {
    renderApp(clinicFetch({ '/api/v1/audit-events': page }), '/audit')
    const row = await screen.findByText('READ_PATIENT', { exact: false })
    expect(row).toHaveTextContent('12:00:00')
  })

  it('sends the filters and goes back to the first page', async () => {
    const fetch = clinicFetch({ '/api/v1/audit-events': page })
    renderApp(fetch, '/audit')
    await screen.findByText('READ_PATIENT', { exact: false })
    await click(screen.getByRole('button', { name: 'Próxima' }))
    await waitFor(() => expect(new URL(fetch.calls.at(-1)!.url).searchParams.get('page')).toBe('1'))
    await type(screen.getByLabelText('Ação'), 'LOGIN')
    await click(screen.getByRole('button', { name: 'Filtrar' }))
    await waitFor(() => expect(new URL(fetch.calls.at(-1)!.url).searchParams.get('action')).toBe('LOGIN'))
    expect(new URL(fetch.calls.at(-1)!.url).searchParams.get('page')).toBe('0')
  })

  it('disables the pagination at the edges and shows an empty state', async () => {
    renderApp(clinicFetch({ '/api/v1/audit-events': { items: [], page: 0, size: 20, total: 0 } }), '/audit')
    expect(await screen.findByText('Nenhum evento encontrado.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled()
  })

  it('shows PERMISSION_DENIED', async () => {
    renderApp(clinicFetch({ '/api/v1/audit-events': () => problem('PERMISSION_DENIED', 403) }), '/audit')
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para esta ação.')
  })
})

describe('consent types', () => {
  const types = [{ id: 'c1', name: 'LGPD', requiredOnSignup: true, controlsMessaging: false, active: true, currentVersion: { number: 2, text: 'v2' } }]
  const detail = { ...types[0], currentVersion: { number: 2, text: 'Texto dois' }, versions: [{ number: 1, text: 'Texto um' }, { number: 2, text: 'Texto dois' }] }
  const offers = [{ id: 't1', name: 'Imagem', text: 'x', requiredOnSignup: false, controlsMessaging: false }]
  const base = { '/api/v1/consent-types': types, '/api/v1/consent-templates': offers, '/api/v1/consent-types/c1': detail }

  it('lists the types with the current version and the platform offers', async () => {
    renderApp(clinicFetch(base), '/consent-types')
    expect(await screen.findByText('LGPD')).toBeInTheDocument()
    expect(screen.getByText('Versão 2')).toBeInTheDocument()
    expect(screen.getByText('(obrigatório)')).toBeInTheDocument()
    expect(screen.getByText('Imagem')).toBeInTheDocument()
  })

  it('validates the name and text', async () => {
    const fetch = clinicFetch(base)
    renderApp(fetch, '/consent-types')
    await click(await screen.findByRole('button', { name: 'Novo tipo' }))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getAllByText('Campo obrigatório.')).toHaveLength(2)
    expect(calls(fetch, 'POST', '/api/v1/consent-types')).toHaveLength(0)
  })

  it('creates a type', async () => {
    const fetch = clinicFetch({ ...base, 'POST /api/v1/consent-types': jsonResponse(detail, 201) })
    renderApp(fetch, '/consent-types')
    await click(await screen.findByRole('button', { name: 'Novo tipo' }))
    await type(screen.getByLabelText('Nome'), 'Fotos')
    await type(screen.getByLabelText('Texto'), 'Autorizo')
    await click(screen.getByLabelText('Obrigatório no cadastro'))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'POST', '/api/v1/consent-types')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/consent-types')[0]!)).toEqual({ name: 'Fotos', text: 'Autorizo', requiredOnSignup: true, controlsMessaging: false })
  })

  it('opens a type with its version history and sends the new text', async () => {
    const fetch = clinicFetch({ ...base, 'PUT /api/v1/consent-types/c1': jsonResponse(detail) })
    renderApp(fetch, '/consent-types')
    await click(await screen.findByRole('button', { name: 'Editar' }))
    expect(await screen.findByDisplayValue('Texto dois')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Versões do texto' })).getAllByRole('listitem')).toHaveLength(2)
    const text = screen.getByLabelText('Texto')
    await userEvent.clear(text)
    await type(text, 'Texto três')
    await click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(calls(fetch, 'PUT', '/api/v1/consent-types/c1')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'PUT', '/api/v1/consent-types/c1')[0]!)).toEqual({ name: 'LGPD', text: 'Texto três', requiredOnSignup: true, controlsMessaging: false, active: true })
  })

  it('imports a platform template', async () => {
    const fetch = clinicFetch({ ...base, 'POST /api/v1/consent-types/import-template': jsonResponse(detail, 201) })
    renderApp(fetch, '/consent-types')
    await click(await screen.findByRole('button', { name: 'Importar' }))
    await waitFor(() => expect(calls(fetch, 'POST', '/api/v1/consent-types/import-template')).toHaveLength(1))
    expect(await bodyOf(calls(fetch, 'POST', '/api/v1/consent-types/import-template')[0]!)).toEqual({ templateId: 't1' })
  })

  it('shows CONSENT_MESSAGING_TYPE_EXISTS', async () => {
    renderApp(clinicFetch({ ...base, 'POST /api/v1/consent-types': () => problem('CONSENT_MESSAGING_TYPE_EXISTS', 409) }), '/consent-types')
    await click(await screen.findByRole('button', { name: 'Novo tipo' }))
    await type(screen.getByLabelText('Nome'), 'Msg')
    await type(screen.getByLabelText('Texto'), 'Aceito')
    await click(screen.getByLabelText('Controla o envio de mensagens'))
    await click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe um consentimento que controla o envio de mensagens.')
  })
})

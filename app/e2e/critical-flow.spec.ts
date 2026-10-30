import { expect, test } from '@playwright/test'

// Texto de teste do consentimento de tratamento de dados (docs/consent/textos-de-teste.md): sem validade jurídica.
const CONSENT_TEXT =
  'Autorizo a clínica a tratar meus dados pessoais (identificação, contato e endereço) para realizar meu cadastro, meu atendimento e a cobrança dos serviços prestados. [TEXTO DE TESTE]'

// Fluxo crítico do M0 de ponta a ponta, contra o Compose local (OIDC, Postgres), o backend e o app:
// login -> administração cria a clínica -> escolha da clínica -> tipo de consentimento -> cadastro de paciente com aceite.
test('login, clinic choice and patient registration with consent', async ({ page }) => {
  const clinicName = `Clínica E2E ${Date.now()}`
  if (process.env.E2E_DEBUG) {
    page.on('console', (message) => console.log('console:', message.text()))
    page.on('response', (response) => response.url().includes('/api/') && console.log(response.status(), response.request().method(), new URL(response.url()).pathname))
    page.on('request', (request) => request.url().includes('/api/') && console.log('req', request.method(), new URL(request.url()).pathname))
    page.on('requestfinished', (request) => request.url().includes('/api/') && console.log('fin', request.method(), new URL(request.url()).pathname))
    page.on('requestfailed', (request) => console.log('failed:', request.url(), request.failure()?.errorText))
  }

  // Login no mock OIDC (Authorization Code + PKCE) como "local-user", criado como PLATFORM_ADMIN no setup.
  await page.goto('/')
  // O mock OIDC (docker/oidc/oidc-config.json) emite sempre sub "local-user" e aud "clinic-api", seja qual for o usuário digitado.
  await page.locator('input[name=username]').fill('local-user')
  await page.locator('input[type=submit]').click()
  await page.waitForURL((url) => url.port !== '8081' && !url.pathname.startsWith('/auth/')) // callback concluído

  // Administração da plataforma: cria a clínica com o próprio usuário como administrador.
  await page.goto('/admin/clinics')
  await page.getByRole('link', { name: 'Nova clínica' }).click()
  await page.getByLabel('Nome da clínica').fill(clinicName)
  await page.getByLabel('E-mail do administrador').fill('e2e@clinic.test')
  await page.getByLabel('Nome do administrador').fill('Usuário E2E')
  await page.getByRole('button', { name: 'Criar clínica' }).click()
  await expect(page.getByRole('heading', { name: clinicName })).toBeVisible()

  // Escolha da clínica (em execuções repetidas o usuário tem várias; na primeira ela é escolhida sozinha).
  await page.getByRole('link', { name: 'Voltar ao app' }).click()
  const chooser = page.getByRole('button', { name: clinicName })
  const menu = page.getByRole('navigation', { name: 'Menu principal' })
  await expect(chooser.or(menu)).toBeVisible()
  if (await chooser.isVisible()) await chooser.click()
  await expect(menu).toBeVisible()
  await expect(page.getByText(clinicName).first()).toBeVisible()

  // Tipo de consentimento obrigatório no cadastro.
  await menu.getByRole('link', { name: 'Consentimentos' }).click()
  await page.getByRole('button', { name: 'Novo tipo' }).click()
  await page.getByLabel('Nome', { exact: true }).fill('Termo E2E')
  await page.getByLabel('Texto').fill(CONSENT_TEXT)
  await page.getByLabel('Obrigatório no cadastro').check()
  await page.getByRole('button', { name: 'Salvar' }).click()
  await expect(page.getByText('Termo E2E')).toBeVisible()

  // Cadastro do paciente: sem o aceite obrigatório não envia; com ele, cadastra.
  await menu.getByRole('link', { name: 'Pacientes' }).click()
  await page.getByRole('link', { name: 'Novo paciente' }).click()
  await expect(page.getByRole('heading', { name: 'Novo paciente' })).toBeVisible()
  await page.getByRole('textbox', { name: 'CPF', exact: true }).fill('529.982.247-25')
  await page.getByLabel('Nome completo').fill('Maria Exemplo Souza')
  await page.getByLabel('Data de nascimento').fill('1980-05-17')
  await page.getByLabel('WhatsApp').fill('11999990000')
  await page.getByLabel('CEP').fill('01310100')
  await page.getByLabel('Rua').fill('Avenida Paulista')
  await page.getByLabel('Número').fill('1000')
  await page.getByLabel('Bairro').fill('Bela Vista')
  await page.getByLabel('Cidade').fill('São Paulo')
  await page.getByLabel('UF').fill('SP')
  await page.getByRole('button', { name: 'Cadastrar paciente' }).click()
  await expect(page.getByRole('alert')).toContainText('Aceite todos os consentimentos obrigatórios')

  await page.getByLabel('Termo E2E (obrigatório)').check()
  await page.getByRole('button', { name: 'Cadastrar paciente' }).click()
  await expect(page.getByRole('heading', { name: 'Maria Exemplo Souza' })).toBeVisible()
  await expect(page.getByRole('list', { name: 'Consentimentos' })).toContainText('Termo E2E')

  // O CPF nunca aparece na URL.
  expect(page.url()).not.toContain('52998224725')
  expect(page.url()).not.toContain('529.982')

  // Edição do paciente.
  await page.getByRole('link', { name: 'Editar paciente' }).click()
  await expect(page.getByRole('textbox', { name: 'CPF', exact: true })).toHaveValue('529.982.247-25') // o administrador pode trocar o CPF
  await page.getByLabel('Nome completo').fill('Maria Exemplo Souza Lima')
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(page.getByRole('heading', { name: 'Maria Exemplo Souza Lima' })).toBeVisible()

  // Revogação do consentimento.
  await page.getByRole('button', { name: 'Revogar' }).click()
  await expect(page.getByRole('list', { name: 'Consentimentos' })).toContainText('revogado em')

  // Tela de notificações (a confirmação do aceite é enfileirada para os canais cadastrados).
  await menu.getByRole('link', { name: 'Notificações' }).click()
  await expect(page.getByRole('heading', { name: 'Notificações' })).toBeVisible()
})

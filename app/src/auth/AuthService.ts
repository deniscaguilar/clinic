/** Porta de autenticação: a interface não depende da biblioteca OIDC, o que permite um serviço falso nos testes. */
export interface AuthService {
  /** Redireciona ao provedor de identidade; `returnTo` é o caminho do app para onde voltar depois do login. */
  login(returnTo: string): Promise<void>
  /** Conclui o retorno do provedor (rota de callback) e devolve o caminho para onde voltar. */
  completeLogin(): Promise<string>
  /** Token de acesso vigente, ou `null` sem sessão ou com sessão expirada. */
  getAccessToken(): Promise<string | null>
  logout(): Promise<void>
}

/** Só caminhos internos do app: evita redirecionamento aberto via `state`. */
export function safeReturnTo(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/'
}

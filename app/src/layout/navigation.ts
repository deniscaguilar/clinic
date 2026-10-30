/** Itens do menu da clínica e a permissão que cada um exige (as permissões vêm da sessão; o backend decide de novo em cada chamada). */
export interface NavItem {
  path: string
  labelKey: string
  permission: string
}

export const NAV_ITEMS: NavItem[] = [
  { path: '/patients', labelKey: 'nav.patients', permission: 'VIEW_PATIENTS' },
  { path: '/practitioners', labelKey: 'nav.practitioners', permission: 'MANAGE_PRACTITIONERS' },
  { path: '/procedures', labelKey: 'nav.procedures', permission: 'MANAGE_PROCEDURES' },
  { path: '/consent-types', labelKey: 'nav.consentTypes', permission: 'MANAGE_CONSENT_TYPES' },
  { path: '/users', labelKey: 'nav.users', permission: 'MANAGE_USERS' },
  { path: '/roles', labelKey: 'nav.roles', permission: 'MANAGE_ROLES' },
  { path: '/branding', labelKey: 'nav.branding', permission: 'MANAGE_BRANDING' },
  { path: '/audit', labelKey: 'nav.audit', permission: 'VIEW_AUDIT_TRAIL' },
  { path: '/notifications', labelKey: 'nav.notifications', permission: 'VIEW_NOTIFICATIONS' },
]

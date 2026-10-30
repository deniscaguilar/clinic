import { Route, Routes } from 'react-router'
import { AdminClinicDetail } from './admin/AdminClinicDetail'
import { AdminClinicNew } from './admin/AdminClinicNew'
import { AdminClinics } from './admin/AdminClinics'
import { AdminLayout } from './admin/AdminLayout'
import { AdminTemplateForm } from './admin/AdminTemplateForm'
import { AdminTemplates } from './admin/AdminTemplates'
import { AuthCallback, RequireAuth } from './auth/AuthProvider'
import { BrandingApplier } from './branding/BrandingApplier'
import { ClinicChooser } from './clinic/ClinicChooser'
import { AuditPage } from './clinicadmin/AuditPage'
import { BrandingPage } from './clinicadmin/BrandingPage'
import { ConsentTypesPage } from './clinicadmin/ConsentTypesPage'
import { PractitionersPage } from './clinicadmin/PractitionersPage'
import { ProceduresPage } from './clinicadmin/ProceduresPage'
import { RolesPage } from './clinicadmin/RolesPage'
import { UsersPage } from './clinicadmin/UsersPage'
import { PatientDetailPage } from './patients/PatientDetailPage'
import { PatientNewPage } from './patients/PatientNewPage'
import { PatientsPage } from './patients/PatientsPage'
import { Shell } from './layout/Shell'
import { SessionProvider } from './session/SessionProvider'
import { Navigate } from 'react-router'

export function App() {
  return (
    <Routes>
      <Route path="/auth/callback" element={<AuthCallback />} />
      {/* O PLATFORM_ADMIN não pertence a clínica: o backoffice fica fora do portão de clínica. */}
      <Route
        path="/admin"
        element={
          <RequireAuth>
            <AdminLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="clinics" replace />} />
        <Route path="clinics" element={<AdminClinics />} />
        <Route path="clinics/new" element={<AdminClinicNew />} />
        <Route path="clinics/:clinicId" element={<AdminClinicDetail />} />
        <Route path="consent-templates" element={<AdminTemplates />} />
        <Route path="consent-templates/new" element={<AdminTemplateForm />} />
        <Route path="consent-templates/:id" element={<AdminTemplateForm />} />
      </Route>
      <Route
        element={
          <RequireAuth>
            <ClinicChooser>
              <SessionProvider>
                <BrandingApplier />
                <Shell />
              </SessionProvider>
            </ClinicChooser>
          </RequireAuth>
        }
      >
        <Route path="patients" element={<PatientsPage />} />
        <Route path="patients/new" element={<PatientNewPage />} />
        <Route path="patients/:id" element={<PatientDetailPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="roles" element={<RolesPage />} />
        <Route path="branding" element={<BrandingPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="practitioners" element={<PractitionersPage />} />
        <Route path="procedures" element={<ProceduresPage />} />
        <Route path="consent-types" element={<ConsentTypesPage />} />
        <Route path="*" element={null} />
      </Route>
    </Routes>
  )
}

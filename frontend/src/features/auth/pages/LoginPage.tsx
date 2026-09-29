import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { AuthLayout } from '@/components/templates/AuthLayout'
import { useMutation } from '@/lib/useQuery'
import { LoginForm } from '../components/LoginForm'
import { useAuth } from '../hooks/useAuth'

interface LocationState {
  from?: string
}

export function LoginPage() {
  const { isAuthenticated, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as LocationState | null)?.from ?? '/'

  const mutation = useMutation(async (values: { email: string; password: string }) => {
    await login(values.email, values.password)
  })

  if (isAuthenticated) return <Navigate to={from} replace />

  const handleSubmit = (values: { email: string; password: string }) => {
    mutation
      .mutate(values)
      .then(() => navigate(from, { replace: true }))
      .catch(() => {
        /* error is surfaced through mutation.error */
      })
  }

  return (
    <AuthLayout
      title="Iniciar sesión"
      subtitle="Acceda con su correo y contraseña."
      footer={
        <span>
          ¿Aún no tiene cuenta?{' '}
          <Link to="/register" className="font-medium text-brand-700 hover:underline">
            Regístrese
          </Link>
        </span>
      }
    >
      <LoginForm
        loading={mutation.loading}
        error={mutation.error}
        fieldErrors={mutation.fieldErrors}
        onSubmit={handleSubmit}
      />
    </AuthLayout>
  )
}

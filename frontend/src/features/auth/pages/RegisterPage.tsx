import { Link, Navigate, useNavigate } from 'react-router'
import { AuthLayout } from '@/components/templates/AuthLayout'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import { authApi } from '../api'
import { RegisterForm } from '../components/RegisterForm'
import { useAuth } from '../hooks/useAuth'
import { toRegisterRequest, type RegisterFormValues } from '../validation'

export function RegisterPage() {
  const { isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const mutation = useMutation((values: RegisterFormValues) => authApi.register(toRegisterRequest(values)))

  if (isAuthenticated) return <Navigate to="/" replace />

  const handleSubmit = (values: RegisterFormValues) => {
    mutation
      .mutate(values)
      .then(() => {
        toast.success('Su cuenta fue creada. Ahora puede iniciar sesión.')
        navigate('/login', { replace: true })
      })
      .catch(() => {
        /* error is surfaced through mutation.error / fieldErrors */
      })
  }

  return (
    <AuthLayout
      title="Crear cuenta"
      subtitle="Regístrese como jugador o invitado para participar en el torneo."
      wide
      footer={
        <span>
          ¿Ya tiene cuenta?{' '}
          <Link to="/login" className="font-medium text-brand-700 hover:underline">
            Inicie sesión
          </Link>
        </span>
      }
    >
      <RegisterForm
        loading={mutation.loading}
        error={mutation.error}
        fieldErrors={mutation.fieldErrors}
        onSubmit={handleSubmit}
      />
    </AuthLayout>
  )
}

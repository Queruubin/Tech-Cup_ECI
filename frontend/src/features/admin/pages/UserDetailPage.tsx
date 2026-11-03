import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { Avatar } from '@/components/atoms/Avatar'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Card } from '@/components/molecules/Card'
import { EmptyState } from '@/components/molecules/EmptyState'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { playersApi } from '@/features/players/api'
import { errorMessage } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { formatPhone } from '@/lib/phone'
import { ACADEMIC_PROGRAM_LABELS, DOCUMENT_TYPE_LABELS, POSITION_LABELS, SCHOOL_RELATION_LABELS } from '@/lib/labels'
import { useMutation, useQuery } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import { adminApi } from '../api'
import { REMOVE_PHOTO_QUESTION } from '../photoModeration'

/** Placeholder for the personal data an ORGANIZER does not receive (birth date, identity document). */
const ADMIN_ONLY_VALUE = 'Solo visible para administradores'

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs font-medium uppercase tracking-wide text-stone-500">{label}</dt>
      <dd className="mt-0.5 break-words text-sm text-ink">{children}</dd>
    </div>
  )
}

/**
 * Staff view of one user (ADMIN and ORGANIZER, reached from a team roster): account data, sport
 * profile and team. Organizers get the e-mail and the phone but not the birth date nor the identity document,
 * like in the users directory; an ADMIN may also remove the player's photo (moderation).
 */
export function UserDetailPage() {
  const params = useParams<{ id: string }>()
  const userId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null
  const { hasRole } = useAuth()
  const isAdmin = hasRole('ADMIN')
  const userQuery = useQuery((signal) => adminApi.getUser(userId as number, signal), [userId], {
    enabled: userId !== null,
  })
  const profileQuery = useQuery((signal) => playersApi.getProfile(userId as number, signal), [userId], {
    enabled: userId !== null,
    nullOnStatus: [404],
  })
  const [removeOpen, setRemoveOpen] = useState(false)
  const removePhoto = useMutation(async (id: number) => {
    const updated = await adminApi.removeUserPhoto(id)
    profileQuery.setData(updated)
    return updated
  })

  if (userId === null) {
    return <EmptyState title="Usuario no encontrado" description="El identificador del usuario no es válido." />
  }

  const user = userQuery.data
  const profile = profileQuery.data
  const personal = (value: string | null | undefined): string => value ?? (isAdmin ? '—' : ADMIN_ONLY_VALUE)

  const confirmRemove = () => {
    removePhoto
      .mutate(userId)
      .then(() => toast.success('Foto eliminada.'))
      .catch((cause: unknown) => toast.error(errorMessage(cause, 'No fue posible quitar la foto.')))
      .finally(() => setRemoveOpen(false))
  }

  return (
    <QueryState loading={userQuery.loading} error={userQuery.error} onRetry={userQuery.refetch}>
      {user && (
        <>
          <PageHeader
            title={user.fullName}
            description={
              <span className="flex flex-wrap items-center gap-2">
                <StatusBadge kind="user" value={user.status} />
                {user.roles.map((role) => (
                  <StatusBadge key={role} kind="role" value={role} />
                ))}
              </span>
            }
          />
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
            <Card title="Datos de la cuenta" className="lg:col-span-2">
              <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label="Correo electrónico">{user.email ?? '—'}</Field>
                <Field label="Celular">
                  {user.phoneNumber ? (
                    <a href={`tel:${user.phoneNumber}`} className="font-medium text-brand-700 underline-offset-2 hover:underline">
                      {formatPhone(user.phoneNumber)}
                    </a>
                  ) : (
                    'Sin celular registrado'
                  )}
                </Field>
                <Field label="Fecha de nacimiento">{user.birthDate ? formatDate(user.birthDate) : personal(null)}</Field>
                <Field label="Tipo de documento">
                  {user.documentType ? DOCUMENT_TYPE_LABELS[user.documentType] : personal(null)}
                </Field>
                <Field label="Número de documento">{personal(user.documentNumber)}</Field>
                <Field label="Relación con la Escuela">
                  {user.schoolRelation ? SCHOOL_RELATION_LABELS[user.schoolRelation] : 'Sin relación'}
                </Field>
                <Field label="Programa académico">
                  {user.academicProgram ? ACADEMIC_PROGRAM_LABELS[user.academicProgram] : 'Sin programa'}
                </Field>
                {user.schoolRelation === 'STUDENT' && <Field label="Semestre">{user.semester ?? '—'}</Field>}
              </dl>
            </Card>

            <Card title="Perfil deportivo">
              <QueryState loading={profileQuery.loading} error={profileQuery.error} onRetry={profileQuery.refetch}>
                {profile ? (
                  <div className="flex flex-col gap-4">
                    <div className="flex items-center gap-4">
                      <Avatar name={profile.fullName} photoFileId={profile.photoFileId} size="xl" />
                      {isAdmin && profile.photoFileId && (
                        <Button size="sm" variant="ghost" onClick={() => setRemoveOpen(true)}>
                          Quitar foto
                        </Button>
                      )}
                    </div>
                    <dl className="grid grid-cols-2 gap-4">
                      <Field label="Posición">{POSITION_LABELS[profile.position]}</Field>
                      <Field label="Dorsal">{profile.jerseyNumber}</Field>
                      <div className="col-span-2">
                        <Field label="Equipo">
                          {profile.teamId ? (
                            <Link to={`/teams/${profile.teamId}`} className="font-medium hover:text-brand-700 hover:underline">
                              {profile.teamName ?? `Equipo ${profile.teamId}`}
                            </Link>
                          ) : (
                            'Sin equipo'
                          )}
                        </Field>
                      </div>
                    </dl>
                  </div>
                ) : (
                  <p className="text-sm text-stone-600">Este usuario no tiene perfil deportivo.</p>
                )}
              </QueryState>
            </Card>
          </div>

          {isAdmin && (
            <ConfirmDialog
              open={removeOpen}
              title={`Quitar la foto de ${user.fullName}`}
              description={REMOVE_PHOTO_QUESTION}
              confirmLabel="Quitar foto"
              danger
              loading={removePhoto.loading}
              onConfirm={confirmRemove}
              onCancel={() => setRemoveOpen(false)}
            />
          )}
        </>
      )}
    </QueryState>
  )
}

import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { Avatar } from '@/components/atoms/Avatar'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog } from '@/components/molecules/Modal'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { authApi } from '@/features/auth/api'
import { ChangePasswordForm } from '@/features/auth/components/ChangePasswordForm'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { IMAGE_ACCEPT, IMAGE_HINT, MAX_UPLOAD_BYTES } from '@/lib/uploads'
import { useMutation } from '@/lib/useQuery'
import { useAuthStore } from '@/store/auth.store'
import { toast } from '@/store/ui.store'
import type { ChangePasswordRequest, UpdateUserRequest, UpsertProfileRequest } from '@/types/api'
import { playersApi } from '../api'
import { BasicInfoForm } from '../components/BasicInfoForm'
import { PHONE_FIELD_HASH, PHONE_INPUT_ID } from '../phoneField'
import { ProfileForm } from '../components/ProfileForm'
import { useMyProfile } from '../hooks/usePlayers'

export function ProfilePage() {
  const { user, hasRole, hasExactRole, refreshMe } = useAuth()
  // Personal scope: an ADMIN without the PLAYER role has no sports profile to create.
  const isPlayer = hasExactRole('PLAYER')
  const profileQuery = useMyProfile(isPlayer)
  const profile = profileQuery.data
  const [photo, setPhoto] = useState<File | null>(null)
  const [removePhotoOpen, setRemovePhotoOpen] = useState(false)
  // Remounts the password form (clearing its fields) after a successful change.
  const [passwordFormKey, setPasswordFormKey] = useState(0)
  const location = useLocation()
  const hasUser = !!user
  // On a phone the sport-profile cards sit above the field and grow once loaded; wait for them.
  const profileLoading = profileQuery.loading

  // "Agregar mi celular" (captain banner) links to /profile#celular: bring the phone field into view.
  useEffect(() => {
    if (location.hash !== PHONE_FIELD_HASH || !hasUser || profileLoading) return
    const input = document.getElementById(PHONE_INPUT_ID)
    if (!input) return
    input.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
    input.focus({ preventScroll: true })
  }, [location.hash, location.key, hasUser, profileLoading])

  const inTeam = !!(profile?.teamId ?? user?.teamId)
  // Photo, position and personal data stay editable in a team; only the jersey freezes while the
  // team plays a tournament (the server answers 409 otherwise).
  const jerseyLocked = !!profile?.teamLocked

  const saveProfile = useMutation(async (payload: UpsertProfileRequest) => {
    const saved = await playersApi.upsertMyProfile(payload)
    profileQuery.setData(saved)
    await refreshMe().catch(() => null)
    return saved
  })

  const uploadPhoto = useMutation(async (file: File) => {
    const saved = await playersApi.uploadMyPhoto(file)
    profileQuery.setData(saved)
    return saved
  })

  const removePhoto = useMutation(async () => {
    const saved = await playersApi.removeMyPhoto()
    profileQuery.setData(saved)
    return saved
  })

  const saveBasicInfo = useMutation(async (payload: UpdateUserRequest) => {
    if (!user) throw new Error('Sesión no disponible.')
    const updated = await playersApi.updateUser(user.id, payload)
    useAuthStore.setState({ user: updated })
    // Reload the session user so everything derived from it (e.g. the captain phone banner) updates
    // without a page reload; the PATCH answer above already covers a failed refresh.
    await refreshMe().catch(() => null)
    return updated
  })

  const changePassword = useMutation((payload: ChangePasswordRequest) => authApi.changePassword(payload))

  const handleSaveProfile = (payload: UpsertProfileRequest) => {
    saveProfile
      .mutate(payload)
      .then(() => toast.success(profile ? 'Perfil deportivo actualizado.' : 'Perfil deportivo creado.'))
      .catch(() => undefined)
  }

  const handleChangePassword = (payload: ChangePasswordRequest) => {
    changePassword
      .mutate(payload)
      .then(() => {
        toast.success('Contraseña actualizada.')
        setPasswordFormKey((value) => value + 1)
      })
      .catch(() => undefined)
  }

  const handleUploadPhoto = () => {
    if (!photo) return
    uploadPhoto
      .mutate(photo)
      .then(() => {
        toast.success('Foto actualizada.')
        setPhoto(null)
      })
      .catch(() => undefined)
  }

  const handleRemovePhoto = () => {
    removePhoto
      .mutate()
      .then(() => {
        toast.success('Foto eliminada.')
        setRemovePhotoOpen(false)
      })
      .catch((cause: unknown) => {
        toast.error(cause instanceof Error ? cause.message : 'No fue posible eliminar la foto.')
        setRemovePhotoOpen(false)
      })
  }

  const handleSaveBasicInfo = (payload: UpdateUserRequest) => {
    saveBasicInfo
      .mutate(payload)
      .then(() => toast.success('Información actualizada.'))
      .catch(() => undefined)
  }

  return (
    <>
      <PageHeader title="Mi perfil" description="Información básica y perfil deportivo." />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="flex flex-col gap-6 lg:col-span-2">
          {isPlayer ? (
            <QueryState loading={profileQuery.loading} error={profileQuery.error} onRetry={profileQuery.refetch}>
              <Card
                title="Perfil deportivo"
                description={
                  profile
                    ? 'Posición y dorsal con los que participa en el torneo.'
                    : 'Cree su perfil deportivo para poder unirse a un equipo.'
                }
              >
                <ProfileForm
                  key={profile ? `${profile.position}-${profile.jerseyNumber}` : 'new'}
                  profile={profile}
                  jerseyLocked={jerseyLocked}
                  loading={saveProfile.loading}
                  error={saveProfile.error}
                  fieldErrors={saveProfile.fieldErrors}
                  onSubmit={handleSaveProfile}
                />
              </Card>

              {profile && (
                <Card title="Foto de perfil" description="Imagen visible en la plantilla de su equipo.">
                  <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
                    <Avatar name={profile.fullName} photoFileId={profile.photoFileId} size="xl" />
                    <div className="flex flex-1 flex-col gap-2">
                      <FileInput
                        accept={IMAGE_ACCEPT}
                        maxBytes={MAX_UPLOAD_BYTES}
                        value={photo}
                        onChange={setPhoto}
                        invalid={!!uploadPhoto.error}
                        hint={IMAGE_HINT}
                      />
                      {uploadPhoto.error && <p className="text-xs font-medium text-brand-600">{uploadPhoto.error}</p>}
                      <div className="flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={!photo}
                          loading={uploadPhoto.loading}
                          onClick={handleUploadPhoto}
                        >
                          Subir foto
                        </Button>
                        {profile.photoFileId && (
                          <Button size="sm" variant="ghost" onClick={() => setRemovePhotoOpen(true)}>
                            Quitar foto
                          </Button>
                        )}
                      </div>
                    </div>
                  </div>
                </Card>
              )}
            </QueryState>
          ) : (
            <Card title="Perfil deportivo">
              <p className="text-sm text-stone-600">
                Solo los usuarios con rol de jugador tienen perfil deportivo. Si desea participar como jugador,
                contacte al organizador del torneo.
              </p>
            </Card>
          )}
        </div>

        <div className="flex flex-col gap-6">
          {user && (
            <Card title="Información básica">
              <BasicInfoForm
                key={user.phoneNumber ?? 'no-phone'}
                user={user}
                phoneInputId={PHONE_INPUT_ID}
                loading={saveBasicInfo.loading}
                error={saveBasicInfo.error}
                fieldErrors={saveBasicInfo.fieldErrors}
                canEditRelation={hasRole('ADMIN')}
                onSubmit={handleSaveBasicInfo}
              />
            </Card>
          )}
          {user && (
            <Card title="Cambiar contraseña" description="Elija una contraseña que no use en otros servicios.">
              <ChangePasswordForm
                key={passwordFormKey}
                loading={changePassword.loading}
                error={changePassword.error}
                fieldErrors={changePassword.fieldErrors}
                onSubmit={handleChangePassword}
              />
            </Card>
          )}
          {isPlayer && !inTeam && (
            <Card title="Siguiente paso">
              <p className="text-sm text-stone-600">
                {profile
                  ? 'Ya tiene perfil deportivo. Cree su propio equipo o busque uno y envíe su solicitud de vinculación.'
                  : 'Cree su perfil deportivo para poder crear un equipo o solicitar unirse a uno.'}
              </p>
              {profile && (
                <div className="mt-3 flex flex-wrap gap-2">
                  <Link to="/my-team">
                    <Button size="sm">Crear equipo</Button>
                  </Link>
                  <Link to="/teams">
                    <Button size="sm" variant="outline">
                      Ver equipos
                    </Button>
                  </Link>
                </div>
              )}
            </Card>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={removePhotoOpen}
        title="Quitar foto de perfil"
        description="Su foto dejará de mostrarse en la plantilla de su equipo. Puede subir otra cuando quiera."
        confirmLabel="Quitar foto"
        danger
        loading={removePhoto.loading}
        onConfirm={handleRemovePhoto}
        onCancel={() => setRemovePhotoOpen(false)}
      />
    </>
  )
}

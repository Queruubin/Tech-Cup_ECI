import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { ConfirmDialog, Modal } from '@/components/molecules/Modal'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { reloadOnError } from '@/lib/reloadOnError'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import { useAuthStore } from '@/store/auth.store'
import type { CreateRefereeRequest, ResetPasswordRequest, Role, UpdateUserRequest, UserResponse } from '@/types/api'
import { adminApi } from '../api'
import { CreateRefereeForm } from '../components/CreateRefereeForm'
import { EditUserModal } from '../components/EditUserModal'
import { ResetPasswordModal } from '../components/ResetPasswordModal'
import { RolesModal } from '../components/RolesModal'
import { UsersTable } from '../components/UsersTable'
import { useDebouncedValue, useUsers } from '../hooks/useAdmin'

export function UsersAdminPage() {
  const { user: me, hasRole, refreshMe } = useAuth()
  const isAdmin = hasRole('ADMIN')
  const canCreateReferees = hasRole('ORGANIZER')

  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search.trim())
  const usersQuery = useUsers(debouncedSearch)

  const [rolesUser, setRolesUser] = useState<UserResponse | null>(null)
  const [rolesList, setRolesList] = useState<Role[]>([])
  const [toInactivate, setToInactivate] = useState<UserResponse | null>(null)
  const [refereeOpen, setRefereeOpen] = useState(false)
  const [resetUser, setResetUser] = useState<UserResponse | null>(null)
  const [editUser, setEditUser] = useState<UserResponse | null>(null)
  const [busyUserId, setBusyUserId] = useState<number | null>(null)

  const replaceUser = (updated: UserResponse) => {
    usersQuery.setData((previous) => previous?.map((item) => (item.id === updated.id ? updated : item)) ?? null)
  }

  const loadRoles = useMutation(async (user: UserResponse) => {
    const roles = await adminApi.getUserRoles(user.id)
    setRolesList(roles)
    return roles
  })

  const changeRole = useMutation(async (input: { userId: number; role: Role; action: 'assign' | 'remove' }) => {
    // The roles may have changed meanwhile: on failure reload them so the dialog and the list show the server state.
    const rolesDialog = { refetch: () => adminApi.getUserRoles(input.userId).then(setRolesList) }
    const roles = await reloadOnError(
      () => (input.action === 'assign' ? adminApi.assignRole(input.userId, input.role) : adminApi.removeRole(input.userId, input.role)),
      rolesDialog,
      usersQuery,
    )
    setRolesList(roles)
    usersQuery.setData((previous) => previous?.map((item) => (item.id === input.userId ? { ...item, roles } : item)) ?? null)
    // Editing one's own roles must be reflected in the session (nav items, guards) right away.
    if (input.userId === me?.id) await refreshMe().catch(() => null)
    return roles
  })

  const inactivate = useMutation(async (user: UserResponse) => {
    // The user may already be inactive: on failure reload so the row reflects the server state.
    const updated = await reloadOnError(() => adminApi.inactivateUser(user.id), usersQuery)
    replaceUser(updated)
    return updated
  })

  const createReferee = useMutation(async (payload: CreateRefereeRequest) => {
    const created = await adminApi.createReferee(payload)
    usersQuery.refetch()
    return created
  })

  const resetPassword = useMutation((input: { userId: number; payload: ResetPasswordRequest }) =>
    adminApi.resetPassword(input.userId, input.payload),
  )

  const updateUser = useMutation(async (input: { userId: number; payload: UpdateUserRequest }) => {
    const updated = await adminApi.updateUser(input.userId, input.payload)
    replaceUser(updated)
    // Editing one's own data must be reflected in the session right away.
    if (updated.id === me?.id) useAuthStore.setState({ user: updated })
    return updated
  })

  const closeEditUser = () => {
    setEditUser(null)
    updateUser.reset()
  }

  const closeResetPassword = () => {
    setResetUser(null)
    resetPassword.reset()
  }

  const openRoles = (user: UserResponse) => {
    setRolesUser(user)
    setRolesList(user.roles)
    loadRoles.mutate(user).catch(() => undefined)
  }

  const confirmInactivate = () => {
    if (!toInactivate) return
    const target = toInactivate
    setBusyUserId(target.id)
    inactivate
      .mutate(target)
      .then(() => toast.success(`${target.fullName} fue inactivado.`))
      .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible inactivar al usuario.'))
      .finally(() => {
        setBusyUserId(null)
        setToInactivate(null)
      })
  }

  return (
    <>
      <PageHeader
        title="Usuarios"
        description={
          isAdmin
            ? 'Consulte y administre los roles de los usuarios de la plataforma.'
            : 'Consulte los usuarios de la plataforma y cree cuentas de árbitro.'
        }
        actions={
          canCreateReferees && (
            <Button size="sm" onClick={() => setRefereeOpen(true)}>
              Crear árbitro
            </Button>
          )
        }
      />

      <div className="mb-4 max-w-md">
        <Input
          type="search"
          placeholder="Buscar por nombre o correo"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          aria-label="Buscar usuarios"
        />
      </div>

      <QueryState loading={usersQuery.loading} error={usersQuery.error} onRetry={usersQuery.refetch}>
        <UsersTable
          users={usersQuery.data ?? []}
          currentUserId={me?.id ?? null}
          canManageRoles={isAdmin}
          canInactivate={isAdmin}
          canResetPassword={isAdmin}
          canEditUser={isAdmin}
          busyUserId={busyUserId}
          onManageRoles={openRoles}
          onEditUser={setEditUser}
          onInactivate={setToInactivate}
          onResetPassword={setResetUser}
        />
      </QueryState>

      <EditUserModal
        key={editUser ? `edit-${editUser.id}` : 'edit-none'}
        user={editUser}
        loading={updateUser.loading}
        error={updateUser.error}
        fieldErrors={updateUser.fieldErrors}
        onClose={closeEditUser}
        onSubmit={(payload) =>
          editUser &&
          updateUser
            .mutate({ userId: editUser.id, payload })
            .then((updated) => {
              toast.success(`Datos de ${updated.fullName} actualizados.`)
              closeEditUser()
            })
            .catch(() => undefined)
        }
      />

      <ResetPasswordModal
        key={resetUser?.id ?? 'none'}
        user={resetUser}
        loading={resetPassword.loading}
        error={resetPassword.error}
        fieldErrors={resetPassword.fieldErrors}
        onClose={closeResetPassword}
        onSubmit={(payload) =>
          resetUser &&
          resetPassword
            .mutate({ userId: resetUser.id, payload })
            .then(() => {
              toast.success(`Contraseña de ${resetUser.fullName} restablecida.`)
              closeResetPassword()
            })
            .catch(() => undefined)
        }
      />

      <RolesModal
        key={rolesUser?.id ?? 'none'}
        user={rolesUser}
        roles={rolesList}
        loading={loadRoles.loading || changeRole.loading}
        error={changeRole.error ?? loadRoles.error}
        isSelf={rolesUser?.id === me?.id}
        onAssign={(role) =>
          rolesUser &&
          changeRole
            .mutate({ userId: rolesUser.id, role, action: 'assign' })
            .then(() => toast.success('Rol asignado.'))
            .catch(() => undefined)
        }
        onRemove={(role) =>
          rolesUser &&
          changeRole
            .mutate({ userId: rolesUser.id, role, action: 'remove' })
            .then(() => toast.success('Rol removido.'))
            .catch(() => undefined)
        }
        onClose={() => {
          setRolesUser(null)
          changeRole.reset()
        }}
      />

      <ConfirmDialog
        open={toInactivate !== null}
        title="Inactivar usuario"
        description={
          toInactivate
            ? `${toInactivate.fullName} no podrá iniciar sesión. No es posible inactivar usuarios vinculados a un equipo inscrito en un torneo activo o en progreso.`
            : undefined
        }
        confirmLabel="Inactivar"
        danger
        loading={inactivate.loading}
        onConfirm={confirmInactivate}
        onCancel={() => setToInactivate(null)}
      />

      <Modal
        open={refereeOpen}
        onClose={() => setRefereeOpen(false)}
        title="Crear árbitro"
        description="El árbitro podrá consultar los partidos que tenga asignados."
      >
        <CreateRefereeForm
          loading={createReferee.loading}
          error={createReferee.error}
          fieldErrors={createReferee.fieldErrors}
          onCancel={() => setRefereeOpen(false)}
          onSubmit={(payload) =>
            createReferee
              .mutate(payload)
              .then((created) => {
                toast.success(`Árbitro ${created.fullName} creado.`)
                setRefereeOpen(false)
              })
              .catch(() => undefined)
          }
        />
      </Modal>
    </>
  )
}

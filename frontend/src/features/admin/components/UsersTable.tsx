import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Table, type Column } from '@/components/molecules/Table'
import { ACADEMIC_PROGRAM_LABELS, SCHOOL_RELATION_LABELS } from '@/lib/labels'
import type { UserResponse } from '@/types/api'

export interface UsersTableProps {
  users: UserResponse[]
  currentUserId: number | null
  canManageRoles: boolean
  canInactivate: boolean
  /** ADMIN only: shows the "Editar datos" action. */
  canEditUser?: boolean
  /** ADMIN only: shows the "Restablecer contraseña" action. */
  canResetPassword?: boolean
  busyUserId: number | null
  onManageRoles: (user: UserResponse) => void
  onEditUser?: (user: UserResponse) => void
  onInactivate: (user: UserResponse) => void
  onResetPassword?: (user: UserResponse) => void
}

export function UsersTable({
  users,
  currentUserId,
  canManageRoles,
  canInactivate,
  canEditUser = false,
  canResetPassword = false,
  busyUserId,
  onManageRoles,
  onEditUser,
  onInactivate,
  onResetPassword,
}: UsersTableProps) {
  const columns: Column<UserResponse>[] = [
    {
      key: 'user',
      header: 'Usuario',
      cell: (user) => (
        <div className="min-w-0">
          <p className="truncate font-medium text-ink">{user.fullName}</p>
          <p className="truncate text-xs text-stone-500">{user.email ?? '—'}</p>
        </div>
      ),
    },
    {
      key: 'relation',
      header: 'Vínculo',
      hideOnMobile: true,
      cell: (user) => (
        <div>
          <p>{user.schoolRelation ? SCHOOL_RELATION_LABELS[user.schoolRelation] : '—'}</p>
          <p className="text-xs text-stone-500">
            {user.academicProgram ? ACADEMIC_PROGRAM_LABELS[user.academicProgram] : ''}
          </p>
        </div>
      ),
    },
    {
      key: 'roles',
      header: 'Roles',
      cell: (user) => (
        <div className="flex flex-wrap gap-1">
          {user.roles.map((role) => (
            <StatusBadge key={role} kind="role" value={role} />
          ))}
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Estado',
      hideOnMobile: true,
      cell: (user) => <StatusBadge kind="user" value={user.status} />,
    },
    {
      key: 'actions',
      header: 'Acciones',
      align: 'right',
      cell: (user) => {
        const busy = busyUserId === user.id
        const isSelf = user.id === currentUserId
        const inactive = user.status === 'INACTIVE'
        return (
          <div className="flex flex-wrap justify-end gap-1.5">
            {canManageRoles && (
              <Button size="sm" variant="outline" onClick={() => onManageRoles(user)} disabled={busy}>
                Roles
              </Button>
            )}
            {canEditUser && onEditUser && (
              <Button size="sm" variant="outline" onClick={() => onEditUser(user)} disabled={busy}>
                Editar datos
              </Button>
            )}
            {canResetPassword && onResetPassword && !inactive && (
              <Button size="sm" variant="outline" onClick={() => onResetPassword(user)} disabled={busy}>
                Restablecer contraseña
              </Button>
            )}
            {canInactivate && !inactive && !isSelf && (
              <Button
                size="sm"
                variant="danger"
                onClick={() => onInactivate(user)}
                disabled={busy}
              >
                Inactivar
              </Button>
            )}
          </div>
        )
      },
    },
  ]

  return <Table columns={columns} rows={users} rowKey={(user) => user.id} empty="No se encontraron usuarios." />
}

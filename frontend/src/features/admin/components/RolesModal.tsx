import { useState } from 'react'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { Modal } from '@/components/molecules/Modal'
import { ROLE_LABELS, toOptions } from '@/lib/labels'
import { ROLES, type Role, type UserResponse } from '@/types/api'

export interface RolesModalProps {
  user: UserResponse | null
  roles: Role[]
  loading: boolean
  error: string | null
  /** True when the target user is the current admin (cannot remove own ADMIN). */
  isSelf: boolean
  onAssign: (role: Role) => void
  onRemove: (role: Role) => void
  onClose: () => void
}

export function RolesModal({ user, roles, loading, error, isSelf, onAssign, onRemove, onClose }: RolesModalProps) {
  const [selected, setSelected] = useState<Role | ''>('')
  const available = ROLES.filter((role) => !roles.includes(role))
  const options = toOptions(available, ROLE_LABELS)
  // Guard against a stale selection (e.g. the role was just assigned).
  const effectiveSelected = selected && available.includes(selected) ? selected : ''

  const assign = () => {
    if (!effectiveSelected) return
    onAssign(effectiveSelected)
    setSelected('')
  }

  return (
    <Modal
      open={user !== null}
      onClose={onClose}
      title="Roles del usuario"
      description={user ? `${user.fullName} · ${user.email ?? '—'}` : undefined}
      footer={
        <Button variant="outline" onClick={onClose}>
          Cerrar
        </Button>
      }
    >
      {error && (
        <Alert kind="error" className="mb-3">
          {error}
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        {roles.length === 0 && <p className="text-sm text-stone-500">El usuario no tiene roles asignados.</p>}
        {roles.map((role) => {
          const blocked = isSelf && role === 'ADMIN'
          return (
            <span key={role} className="inline-flex items-center gap-1 rounded-full bg-stone-50 pr-1 ring-1 ring-stone-200">
              <StatusBadge kind="role" value={role} />
              <button
                type="button"
                aria-label={`Quitar rol ${ROLE_LABELS[role]}`}
                title={blocked ? 'No puede quitarse su propio rol de administrador' : 'Quitar rol'}
                disabled={loading || blocked}
                onClick={() => onRemove(role)}
                className="rounded-full p-0.5 text-stone-400 hover:text-brand-600 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
                  <path d="M6.28 5.22a.75.75 0 00-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 101.06 1.06L10 11.06l3.72 3.72a.75.75 0 101.06-1.06L11.06 10l3.72-3.72a.75.75 0 00-1.06-1.06L10 8.94 6.28 5.22z" />
                </svg>
              </button>
            </span>
          )
        })}
      </div>
      <div className="mt-4 flex items-end gap-2">
        <label className="flex flex-1 flex-col gap-1 text-sm font-medium text-stone-800">
          Asignar rol
          <Select
            options={options}
            placeholder={available.length === 0 ? 'Todos los roles asignados' : 'Seleccione un rol'}
            value={effectiveSelected}
            disabled={available.length === 0 || loading}
            onChange={(event) => setSelected(event.target.value as Role | '')}
          />
        </label>
        <Button onClick={assign} disabled={!effectiveSelected} loading={loading}>
          Asignar
        </Button>
      </div>
    </Modal>
  )
}

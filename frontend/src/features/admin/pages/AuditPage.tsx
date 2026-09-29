import { useState } from 'react'
import { Badge } from '@/components/atoms/Badge'
import { Select } from '@/components/atoms/Select'
import { PageHeader } from '@/components/molecules/PageHeader'
import { QueryState } from '@/components/molecules/QueryState'
import { Table, type Column } from '@/components/molecules/Table'
import { formatDateTime } from '@/lib/format'
import { auditActionLabel } from '@/lib/labels'
import { AUDIT_ACTIONS, type AuditLogResponse } from '@/types/api'
import { useAudit } from '../hooks/useAdmin'

const ACTION_OPTIONS = AUDIT_ACTIONS.map((action) => ({ value: action, label: auditActionLabel(action) }))
const LIMIT_OPTIONS = [
  { value: '50', label: '50 registros' },
  { value: '100', label: '100 registros' },
  { value: '250', label: '250 registros' },
]

function DetailsCell({ details }: { details: Record<string, unknown> | null }) {
  if (!details || Object.keys(details).length === 0) return <span className="text-stone-400">—</span>
  return (
    <details className="max-w-xs">
      <summary className="cursor-pointer text-xs text-brand-700">Ver detalles</summary>
      <pre className="mt-1 max-h-40 overflow-auto rounded-lg bg-stone-50 p-2 text-[11px] text-stone-700">
        {JSON.stringify(details, null, 2)}
      </pre>
    </details>
  )
}

export function AuditPage() {
  const [action, setAction] = useState('')
  const [limit, setLimit] = useState('100')
  const { data, loading, error, refetch } = useAudit(action, Number(limit))

  const columns: Column<AuditLogResponse>[] = [
    { key: 'date', header: 'Fecha', cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.createdAt)}</span> },
    { key: 'action', header: 'Acción', cell: (row) => <Badge tone="info">{auditActionLabel(row.action)}</Badge> },
    {
      key: 'actor',
      header: 'Actor',
      cell: (row) => row.actorName ?? (row.actorUserId !== null ? `Usuario #${row.actorUserId}` : 'Sistema'),
    },
    {
      key: 'entity',
      header: 'Entidad',
      hideOnMobile: true,
      cell: (row) => (
        <span className="text-stone-700">
          {row.entityType}
          {row.entityId !== null && row.entityId !== undefined ? ` #${row.entityId}` : ''}
        </span>
      ),
    },
    { key: 'details', header: 'Detalles', hideOnMobile: true, cell: (row) => <DetailsCell details={row.details} /> },
  ]

  return (
    <>
      <PageHeader
        title="Auditoría"
        description="Registro de acciones relevantes realizadas en la plataforma."
        actions={
          <>
            <Select
              options={ACTION_OPTIONS}
              placeholder="Todas las acciones"
              value={action}
              onChange={(event) => setAction(event.target.value)}
              className="w-56"
              aria-label="Filtrar por acción"
            />
            <Select
              options={LIMIT_OPTIONS}
              value={limit}
              onChange={(event) => setLimit(event.target.value)}
              className="w-40"
              aria-label="Cantidad de registros"
            />
          </>
        }
      />
      <QueryState loading={loading} error={error} onRetry={refetch}>
        <Table columns={columns} rows={data ?? []} rowKey={(row) => row.id} empty="No hay registros de auditoría." dense />
      </QueryState>
    </>
  )
}

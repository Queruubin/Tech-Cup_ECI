import { useState } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { Textarea } from '@/components/atoms/Textarea'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { FormField } from '@/components/molecules/FormField'
import { Modal } from '@/components/molecules/Modal'
import { QueryState } from '@/components/molecules/QueryState'
import { formatDateTime } from '@/lib/format'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { RegistrationResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import { useRegistrations } from '../hooks/useTournaments'
import { FilePreview } from './FilePreview'

type Decision = { registration: RegistrationResponse; action: 'approve' | 'reject' }

export function RegistrationsReview({ tournamentId, onDecided }: { tournamentId: number; onDecided?: () => void }) {
  const query = useRegistrations(tournamentId)
  const [preview, setPreview] = useState<RegistrationResponse | null>(null)
  const [decision, setDecision] = useState<Decision | null>(null)
  const [note, setNote] = useState('')

  const decide = useMutation(async (input: Decision & { note: string }) => {
    const payload = input.note.trim() ? { note: input.note.trim() } : {}
    const updated =
      input.action === 'approve'
        ? await tournamentsApi.approveRegistration(input.registration.id, payload)
        : await tournamentsApi.rejectRegistration(input.registration.id, payload)
    query.setData((previous) => previous?.map((item) => (item.id === updated.id ? updated : item)) ?? null)
    return updated
  })

  const confirmDecision = () => {
    if (!decision) return
    decide
      .mutate({ ...decision, note })
      .then((updated) => {
        toast.success(decision.action === 'approve' ? `Inscripción de ${updated.teamName} aprobada.` : `Inscripción de ${updated.teamName} rechazada.`)
        setDecision(null)
        setNote('')
        onDecided?.()
      })
      .catch(() => undefined)
  }

  const sorted = [...(query.data ?? [])].sort((a, b) => {
    if (a.status !== b.status) return a.status === 'UNDER_REVIEW' ? -1 : b.status === 'UNDER_REVIEW' ? 1 : 0
    return b.createdAt.localeCompare(a.createdAt)
  })
  const pendingCount = sorted.filter((item) => item.status === 'UNDER_REVIEW').length

  return (
    <Card
      title="Inscripciones"
      description={pendingCount > 0 ? `${pendingCount} en revisión` : 'Sin inscripciones pendientes de revisión.'}
      padded={false}
    >
      <QueryState loading={query.loading} error={query.error} onRetry={query.refetch} inline>
        {sorted.length === 0 ? (
          <p className="px-5 py-6 text-sm text-stone-500">Ningún equipo se ha inscrito todavía.</p>
        ) : (
          <ul className="divide-y divide-stone-100">
            {sorted.map((registration) => (
              <li key={registration.id} className="flex flex-col gap-2 px-5 py-3 sm:flex-row sm:items-center">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Link to={`/teams/${registration.teamId}`} className="text-sm font-medium text-ink hover:text-brand-700">
                      {registration.teamName}
                    </Link>
                    <StatusBadge kind="registration" value={registration.status} />
                  </div>
                  <p className="text-xs text-stone-500">
                    Enviada el {formatDateTime(registration.createdAt)}
                    {registration.reviewedAt && ` · Revisada el ${formatDateTime(registration.reviewedAt)}`}
                  </p>
                  {registration.reviewNote && <p className="mt-1 text-xs text-stone-700">Nota: {registration.reviewNote}</p>}
                </div>
                <div className="flex shrink-0 flex-wrap gap-2">
                  {registration.receiptFileId && (
                    <Button size="sm" variant="ghost" onClick={() => setPreview(registration)}>
                      Ver comprobante
                    </Button>
                  )}
                  {registration.status === 'UNDER_REVIEW' && (
                    <>
                      <Button size="sm" onClick={() => setDecision({ registration, action: 'approve' })}>
                        Aprobar
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => setDecision({ registration, action: 'reject' })}>
                        Rechazar
                      </Button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </QueryState>

      <Modal open={preview !== null} onClose={() => setPreview(null)} title="Comprobante de pago" description={preview?.teamName} size="lg">
        <FilePreview fileId={preview?.receiptFileId} alt={`Comprobante de ${preview?.teamName ?? ''}`} />
      </Modal>

      <Modal
        open={decision !== null}
        onClose={() => setDecision(null)}
        title={decision?.action === 'approve' ? 'Aprobar inscripción' : 'Rechazar inscripción'}
        description={decision ? `Equipo: ${decision.registration.teamName}` : undefined}
        footer={
          <>
            <Button variant="outline" onClick={() => setDecision(null)} disabled={decide.loading}>
              Cancelar
            </Button>
            <Button variant={decision?.action === 'approve' ? 'primary' : 'danger'} onClick={confirmDecision} loading={decide.loading}>
              {decision?.action === 'approve' ? 'Aprobar' : 'Rechazar'}
            </Button>
          </>
        }
      >
        {decide.error && (
          <Alert kind="error" className="mb-3">
            {decide.error}
          </Alert>
        )}
        {decision?.action === 'approve' && (
          <p className="mb-3 text-sm text-stone-600">Se verificará nuevamente la capacidad del torneo antes de aprobar.</p>
        )}
        <FormField label="Nota (opcional)" error={decide.fieldErrors.note} hint="Visible para el capitán del equipo.">
          <Textarea value={note} maxLength={300} onChange={(event) => setNote(event.target.value)} />
        </FormField>
      </Modal>
    </Card>
  )
}

import { useState } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { ConfirmDialog, Modal } from '@/components/molecules/Modal'
import { QueryState } from '@/components/molecules/QueryState'
import { EligibilityPanel } from '@/features/teams/components/EligibilityPanel'
import { useEligibility, useMyTeam } from '@/features/teams/hooks/useTeams'
import { formatDate, formatDateTime, formatMoney } from '@/lib/format'
import { MAX_UPLOAD_BYTES, RECEIPT_ACCEPT, RECEIPT_HINT } from '@/lib/uploads'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { TournamentResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import { useMyRegistration } from '../hooks/useTournaments'
import { FilePreview } from './FilePreview'

export interface CaptainRegistrationProps {
  tournament: TournamentResponse
  onRegistered?: () => void
}

export function CaptainRegistration({ tournament, onRegistered }: CaptainRegistrationProps) {
  const teamQuery = useMyTeam()
  const team = teamQuery.data
  const eligibility = useEligibility(team?.id ?? null)
  const registration = useMyRegistration(tournament.id, !!team)
  const [receipt, setReceipt] = useState<File | null>(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)

  const submit = useMutation(async (file: File) => {
    const created = await tournamentsApi.createRegistration(tournament.id, file)
    registration.setData(created)
    return created
  })
  const cancel = useMutation(async (id: number) => {
    const updated = await tournamentsApi.cancelRegistration(id)
    registration.setData(updated)
    return updated
  })

  const isOpen = tournament.status === 'ACTIVE'
  const full = tournament.approvedTeams >= tournament.maxTeams
  // The registration deadline is not enforced here on purpose: the server owns that rule and
  // answers 409 with an explanatory message, which avoids clock/time-zone disagreements.
  const blockers = [
    !isOpen && 'El torneo no está abierto a inscripciones.',
    isOpen && full && 'El torneo alcanzó su cupo máximo de equipos.',
    eligibility.data && !eligibility.data.eligible && 'El equipo aún no cumple los requisitos de elegibilidad.',
  ].filter((item): item is string => typeof item === 'string')

  const current = registration.data
  const showForm = !current || current.status === 'CANCELLED' || current.status === 'REJECTED'

  return (
    <QueryState loading={teamQuery.loading} error={teamQuery.error} onRetry={teamQuery.refetch}>
      {!team ? (
        <Card title="Inscripción">
          <p className="text-sm text-stone-600">Debe crear su equipo antes de inscribirlo en un torneo.</p>
          <Link to="/my-team" className="mt-3 inline-block">
            <Button size="sm">Crear equipo</Button>
          </Link>
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <div className="flex flex-col gap-6 lg:col-span-2">
            <QueryState loading={registration.loading} error={registration.error} onRetry={registration.refetch} inline>
              {current && (
                <Card
                  title={`Inscripción de ${current.teamName}`}
                  description={`Enviada el ${formatDateTime(current.createdAt)}`}
                  actions={<StatusBadge kind="registration" value={current.status} size="md" />}
                >
                  {current.reviewNote && (
                    <Alert kind={current.status === 'APPROVED' ? 'success' : 'info'} title="Nota del organizador" className="mb-3">
                      {current.reviewNote}
                    </Alert>
                  )}
                  {current.status === 'UNDER_REVIEW' && (
                    <p className="mb-3 text-sm text-stone-600">El organizador revisará el comprobante y aprobará o rechazará la inscripción.</p>
                  )}
                  {current.status === 'APPROVED' && (
                    <p className="mb-3 text-sm text-stone-600">Su equipo participa en el torneo. Mientras el torneo esté activo o en progreso, la plantilla queda bloqueada.</p>
                  )}
                  <div className="flex flex-wrap gap-2">
                    {current.receiptFileId && (
                      <Button size="sm" variant="ghost" onClick={() => setPreviewOpen(true)}>
                        Ver comprobante
                      </Button>
                    )}
                    {current.status === 'UNDER_REVIEW' && (
                      <Button size="sm" variant="outline" onClick={() => setCancelOpen(true)}>
                        Cancelar inscripción
                      </Button>
                    )}
                  </div>
                </Card>
              )}

              {showForm && (
                <Card
                  title="Inscribir mi equipo"
                  description={`Costo de inscripción: ${formatMoney(tournament.fee)}. El pago se realiza por fuera de la plataforma; cargue el comprobante de consignación.`}
                >
                  {isOpen && (
                    <p className="mb-3 text-xs text-stone-500">
                      Plazo de inscripción: hasta el {formatDate(tournament.registrationDeadline)}.
                    </p>
                  )}
                  {blockers.length > 0 && (
                    <Alert kind="warning" className="mb-4">
                      <ul className="list-disc pl-5">
                        {blockers.map((item) => (
                          <li key={item}>{item}</li>
                        ))}
                      </ul>
                    </Alert>
                  )}
                  {submit.error && (
                    <Alert kind="error" className="mb-4">
                      {submit.error}
                    </Alert>
                  )}
                  <FileInput
                    accept={RECEIPT_ACCEPT}
                    maxBytes={MAX_UPLOAD_BYTES}
                    value={receipt}
                    onChange={setReceipt}
                    hint={RECEIPT_HINT}
                    invalid={!!submit.fieldErrors.file}
                  />
                  {submit.fieldErrors.file && <p className="mt-1 text-xs font-medium text-brand-600">{submit.fieldErrors.file}</p>}
                  <div className="mt-4">
                    <Button
                      disabled={!receipt || blockers.length > 0}
                      loading={submit.loading}
                      onClick={() =>
                        receipt &&
                        submit
                          .mutate(receipt)
                          .then(() => {
                            toast.success('Inscripción enviada. Quedará en revisión.')
                            setReceipt(null)
                            onRegistered?.()
                          })
                          .catch(() => undefined)
                      }
                    >
                      Enviar inscripción
                    </Button>
                  </div>
                </Card>
              )}
            </QueryState>
          </div>
          <div>
            <EligibilityPanel
              eligibility={eligibility.data}
              loading={eligibility.loading}
              error={eligibility.error}
              onRetry={eligibility.refetch}
            />
          </div>
        </div>
      )}

      <Modal open={previewOpen} onClose={() => setPreviewOpen(false)} title="Comprobante de pago" size="lg">
        <FilePreview fileId={current?.receiptFileId} alt="Comprobante de pago" />
      </Modal>
      <ConfirmDialog
        open={cancelOpen}
        title="Cancelar inscripción"
        description="Podrá inscribirse nuevamente mientras el plazo siga abierto."
        confirmLabel="Sí, cancelar"
        cancelLabel="Volver"
        danger
        loading={cancel.loading}
        onConfirm={() =>
          current &&
          cancel
            .mutate(current.id)
            .then(() => toast.success('Inscripción cancelada.'))
            .catch((cause: unknown) => toast.error(cause instanceof Error ? cause.message : 'No fue posible cancelar.'))
            .finally(() => setCancelOpen(false))
        }
        onCancel={() => setCancelOpen(false)}
      />
    </QueryState>
  )
}

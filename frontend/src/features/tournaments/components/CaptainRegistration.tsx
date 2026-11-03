import { useState } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '@/components/atoms/Badge'
import { Button } from '@/components/atoms/Button'
import { FileInput } from '@/components/atoms/FileInput'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { FormField } from '@/components/molecules/FormField'
import { ConfirmDialog, Modal } from '@/components/molecules/Modal'
import { QueryState } from '@/components/molecules/QueryState'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { EligibilityPanel } from '@/features/teams/components/EligibilityPanel'
import { useEligibility, useMyTeam } from '@/features/teams/hooks/useTeams'
import { canCreateTeam } from '@/features/teams/myTeamView'
import { formatDate, formatDateTime, formatMoney } from '@/lib/format'
import {
  FORM_EVIDENCE_ACCEPT,
  FORM_EVIDENCE_HINT,
  MAX_UPLOAD_BYTES,
  RECEIPT_ACCEPT,
  RECEIPT_HINT,
} from '@/lib/uploads'
import { reloadOnError } from '@/lib/reloadOnError'
import { useMutation } from '@/lib/useQuery'
import { toast } from '@/store/ui.store'
import type { TournamentResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import { useMyRegistration } from '../hooks/useTournaments'
import { needsFormEvidence } from '../registrationEvidence'
import { FilePreview } from './FilePreview'

type PreviewKind = 'receipt' | 'formEvidence'

const PREVIEW_TITLES: Record<PreviewKind, string> = {
  receipt: 'Comprobante de pago',
  formEvidence: 'Evidencia del formulario de la Escuela',
}

export interface CaptainRegistrationProps {
  tournament: TournamentResponse
  onRegistered?: () => void
}

export function CaptainRegistration({ tournament, onRegistered }: CaptainRegistrationProps) {
  const { user } = useAuth()
  const teamQuery = useMyTeam()
  const team = teamQuery.data
  const eligibility = useEligibility(team?.id ?? null)
  const registration = useMyRegistration(tournament.id, !!team)
  const [receipt, setReceipt] = useState<File | null>(null)
  const [formEvidence, setFormEvidence] = useState<File | null>(null)
  const [missingEvidence, setMissingEvidence] = useState<File | null>(null)
  const [preview, setPreview] = useState<PreviewKind | null>(null)
  const [cancelOpen, setCancelOpen] = useState(false)

  const submit = useMutation(async (files: { receipt: File; formEvidence: File }) => {
    const created = await tournamentsApi.createRegistration(tournament.id, files.receipt, files.formEvidence)
    registration.setData(created)
    return created
  })
  const uploadEvidence = useMutation(async (input: { registrationId: number; file: File }) => {
    // The organizer may have decided it meanwhile: on failure reload so the card shows the server state.
    const updated = await reloadOnError(
      () => tournamentsApi.uploadFormEvidence(input.registrationId, input.file),
      registration,
    )
    registration.setData(updated)
    return updated
  })
  const cancel = useMutation(async (id: number) => {
    // The organizer may have decided it meanwhile: on failure reload so the card shows the server state.
    const updated = await reloadOnError(() => tournamentsApi.cancelRegistration(id), registration)
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
  // Registrations sent before the evidence was required: the organizer cannot approve them yet.
  const needsEvidence = needsFormEvidence(current)
  const previewFileId = preview === 'receipt' ? current?.receiptFileId : preview === 'formEvidence' ? current?.formEvidenceFileId : null

  return (
    <QueryState loading={teamQuery.loading} error={teamQuery.error} onRetry={teamQuery.refetch}>
      {!team ? (
        <Card title="Inscripción">
          <p className="text-sm text-stone-600">Debe crear su equipo antes de inscribirlo en un torneo.</p>
          {canCreateTeam(user) && (
            <Link to="/my-team" className="mt-3 inline-block">
              <Button size="sm">Crear equipo</Button>
            </Link>
          )}
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
                  {current.status === 'UNDER_REVIEW' && !needsEvidence && (
                    <p className="mb-3 text-sm text-stone-600">
                      El organizador revisará el comprobante y la evidencia del formulario de la Escuela, y aprobará o rechazará
                      la inscripción.
                    </p>
                  )}
                  {needsEvidence && (
                    <Alert kind="warning" title="Subir evidencia del formulario" className="mb-3">
                      <p className="mb-3">
                        Su inscripción no tiene la evidencia del formulario de la Escuela. El organizador no puede aprobarla
                        hasta que la suba.
                      </p>
                      {uploadEvidence.error && <p className="mb-2 font-medium text-brand-700">{uploadEvidence.error}</p>}
                      <FileInput
                        accept={FORM_EVIDENCE_ACCEPT}
                        maxBytes={MAX_UPLOAD_BYTES}
                        value={missingEvidence}
                        onChange={setMissingEvidence}
                        hint={FORM_EVIDENCE_HINT}
                      />
                      <Button
                        size="sm"
                        className="mt-3"
                        disabled={!missingEvidence}
                        loading={uploadEvidence.loading}
                        onClick={() =>
                          missingEvidence &&
                          uploadEvidence
                            .mutate({ registrationId: current.id, file: missingEvidence })
                            .then(() => {
                              toast.success('Evidencia del formulario cargada.')
                              setMissingEvidence(null)
                            })
                            .catch(() => undefined)
                        }
                      >
                        Subir evidencia
                      </Button>
                    </Alert>
                  )}
                  {current.status === 'APPROVED' && (
                    <p className="mb-3 text-sm text-stone-600">Su equipo participa en el torneo. Mientras el torneo esté activo o en progreso, la plantilla queda bloqueada.</p>
                  )}
                  <div className="flex flex-wrap gap-2">
                    {current.receiptFileId && (
                      <Button size="sm" variant="ghost" onClick={() => setPreview('receipt')}>
                        Ver comprobante
                      </Button>
                    )}
                    {current.formEvidenceFileId && (
                      <Button size="sm" variant="ghost" onClick={() => setPreview('formEvidence')}>
                        Ver formulario
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
                  description={`Costo de inscripción: ${formatMoney(tournament.fee)}. El pago se realiza por fuera de la plataforma. Para inscribirse cargue el comprobante de consignación y la evidencia de que diligenció el formulario de la Escuela.`}
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
                  <div className="flex flex-col gap-4">
                    <FormField label="Comprobante de pago" required error={submit.fieldErrors.file}>
                      <FileInput
                        accept={RECEIPT_ACCEPT}
                        maxBytes={MAX_UPLOAD_BYTES}
                        value={receipt}
                        onChange={setReceipt}
                        hint={RECEIPT_HINT}
                        invalid={!!submit.fieldErrors.file}
                      />
                    </FormField>
                    <FormField
                      label="Evidencia del formulario de la Escuela"
                      required
                      hint="Pantallazo o PDF de la confirmación del formulario de inscripción de la Escuela."
                      error={submit.fieldErrors.formEvidence}
                    >
                      <FileInput
                        accept={FORM_EVIDENCE_ACCEPT}
                        maxBytes={MAX_UPLOAD_BYTES}
                        value={formEvidence}
                        onChange={setFormEvidence}
                        hint={FORM_EVIDENCE_HINT}
                        invalid={!!submit.fieldErrors.formEvidence}
                      />
                    </FormField>
                  </div>
                  <div className="mt-4">
                    <Button
                      disabled={!receipt || !formEvidence || blockers.length > 0}
                      loading={submit.loading}
                      onClick={() =>
                        receipt &&
                        formEvidence &&
                        submit
                          .mutate({ receipt, formEvidence })
                          .then(() => {
                            toast.success('Inscripción enviada. Quedará en revisión.')
                            setReceipt(null)
                            setFormEvidence(null)
                            onRegistered?.()
                          })
                          .catch(() => undefined)
                      }
                    >
                      Enviar inscripción
                    </Button>
                    {(!receipt || !formEvidence) && blockers.length === 0 && (
                      <p className="mt-2 text-xs text-stone-500">
                        Adjunte el comprobante de pago y la evidencia del formulario de la Escuela para enviar la inscripción.
                      </p>
                    )}
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

      <Modal open={preview !== null} onClose={() => setPreview(null)} title={preview ? PREVIEW_TITLES[preview] : ''} size="lg">
        <FilePreview fileId={previewFileId} alt={preview ? PREVIEW_TITLES[preview] : ''} />
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

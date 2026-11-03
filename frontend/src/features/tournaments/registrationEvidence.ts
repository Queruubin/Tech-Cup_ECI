import type { RegistrationResponse } from '@/types/api'

/** Same rule and wording as the server's 409 when an organizer approves a registration without it. */
export const APPROVAL_NEEDS_FORM_EVIDENCE =
  'La inscripción no tiene la evidencia del formulario de la Escuela. El capitán debe subirla antes de aprobarla.'

/** Why "Aprobar" is disabled for this registration, or `null` when it may be approved. */
export function approvalBlocker(registration: Pick<RegistrationResponse, 'status' | 'hasFormEvidence'>): string | null {
  return registration.status === 'UNDER_REVIEW' && !registration.hasFormEvidence ? APPROVAL_NEEDS_FORM_EVIDENCE : null
}

/** The captain still has to upload the evidence (registrations sent before it was required). */
export function needsFormEvidence(registration: Pick<RegistrationResponse, 'status' | 'hasFormEvidence'> | null): boolean {
  return !!registration && registration.status === 'UNDER_REVIEW' && !registration.hasFormEvidence
}

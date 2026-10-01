import { describe, expect, it } from 'vitest'
import { AUDIT_ACTIONS, JOIN_REQUEST_DIRECTIONS } from '@/types/api'
import { AUDIT_ACTION_LABELS, JOIN_REQUEST_DIRECTION_LABELS, auditActionLabel } from './labels'

describe('AUDIT_ACTION_LABELS', () => {
  it('has a non-empty Spanish label for every known audit action', () => {
    for (const action of AUDIT_ACTIONS) {
      expect(AUDIT_ACTION_LABELS[action], `missing label for ${action}`).toEqual(expect.any(String))
      expect(AUDIT_ACTION_LABELS[action].trim().length, `empty label for ${action}`).toBeGreaterThan(0)
    }
  })

  it('does not carry labels for actions the backend does not emit', () => {
    const known = new Set<string>(AUDIT_ACTIONS)
    for (const key of Object.keys(AUDIT_ACTION_LABELS)) {
      expect(known.has(key), `unexpected label ${key}`).toBe(true)
    }
    expect(known.has('MATCH_CREATED')).toBe(false)
    expect(known.has('MATCH_DELETED')).toBe(false)
  })

  it('falls back to the raw value for unknown actions', () => {
    expect(auditActionLabel('LOGIN')).toBe('Inicio de sesión')
    expect(auditActionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})

describe('invitation labels', () => {
  it('labels the invitation audit actions', () => {
    expect(auditActionLabel('INVITATION_SENT')).toBe('Invitación enviada')
    expect(auditActionLabel('INVITATION_ACCEPTED')).toBe('Invitación aceptada')
    expect(auditActionLabel('INVITATION_REJECTED')).toBe('Invitación rechazada')
    expect(auditActionLabel('INVITATION_CANCELLED')).toBe('Invitación cancelada')
  })

  it('labels every join-request direction', () => {
    expect(JOIN_REQUEST_DIRECTIONS.map((direction) => JOIN_REQUEST_DIRECTION_LABELS[direction])).toEqual(['Solicitud', 'Invitación'])
  })
})

describe('match reopen / phase undo audit labels', () => {
  it('labels the new actions', () => {
    expect(auditActionLabel('MATCH_REOPENED')).toBe('Partido reabierto')
    expect(auditActionLabel('PHASE_UNDONE')).toBe('Fase deshecha')
  })

  it('lists them right after MATCH_RESULT_CORRECTED', () => {
    const index = AUDIT_ACTIONS.indexOf('MATCH_RESULT_CORRECTED')
    expect(AUDIT_ACTIONS.slice(index + 1, index + 3)).toEqual(['MATCH_REOPENED', 'PHASE_UNDONE'])
  })
})

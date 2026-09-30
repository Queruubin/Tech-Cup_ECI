import { describe, expect, it } from 'vitest'
import { AUDIT_ACTIONS } from '@/types/api'
import { AUDIT_ACTION_LABELS, auditActionLabel } from './labels'

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

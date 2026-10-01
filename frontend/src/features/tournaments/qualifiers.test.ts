import { describe, expect, it } from 'vitest'
import { qualifierCount } from './qualifiers'

describe('qualifierCount', () => {
  it('mirrors the backend knockout cut: 8+ → 8, 4–7 → 4, 2–3 → 2', () => {
    expect([12, 8, 7, 4, 3, 2].map(qualifierCount)).toEqual([8, 8, 4, 4, 2, 2])
  })

  it('marks nobody when there are fewer than two teams', () => {
    expect(qualifierCount(1)).toBe(0)
    expect(qualifierCount(0)).toBe(0)
  })
})

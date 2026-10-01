import { describe, expect, it, vi } from 'vitest'
import { reloadOnError } from './reloadOnError'

describe('reloadOnError', () => {
  it('returns the result and reloads nothing when the action succeeds', async () => {
    const list = { refetch: vi.fn() }
    await expect(reloadOnError(() => Promise.resolve(42), list)).resolves.toBe(42)
    expect(list.refetch).not.toHaveBeenCalled()
  })

  it('reloads every list and rethrows the original error when the action fails', async () => {
    const requests = { refetch: vi.fn().mockResolvedValue(undefined) }
    const team = { refetch: vi.fn().mockResolvedValue(undefined) }
    const failure = new Error('La solicitud ya fue resuelta.')
    await expect(reloadOnError(() => Promise.reject(failure), requests, team)).rejects.toBe(failure)
    expect(requests.refetch).toHaveBeenCalledTimes(1)
    expect(team.refetch).toHaveBeenCalledTimes(1)
  })

  it('keeps the original error even if a reload fails', async () => {
    const failure = new Error('409')
    const broken = { refetch: vi.fn().mockRejectedValue(new Error('network')) }
    const throwing = {
      refetch: vi.fn(() => {
        throw new Error('sync')
      }),
    }
    await expect(reloadOnError(() => Promise.reject(failure), broken, throwing)).rejects.toBe(failure)
    expect(broken.refetch).toHaveBeenCalled()
    expect(throwing.refetch).toHaveBeenCalled()
  })
})

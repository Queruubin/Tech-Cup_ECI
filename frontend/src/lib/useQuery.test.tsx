import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { useEffect } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { QueryState } from '@/components/molecules/QueryState'
import { useQuery } from './useQuery'

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (cause: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (cause: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useQuery', () => {
  it('reports loading on the first load and refetching (not loading) once data exists', async () => {
    const calls: Deferred<string>[] = []
    const fetcher = vi.fn(() => {
      const next = deferred<string>()
      calls.push(next)
      return next.promise
    })

    const { result } = renderHook(() => useQuery(fetcher, []))
    expect(result.current.loading).toBe(true)
    expect(result.current.refetching).toBe(false)
    expect(result.current.data).toBeNull()

    await act(async () => calls[0]?.resolve('first'))
    expect(result.current.loading).toBe(false)
    expect(result.current.data).toBe('first')

    let refetchPromise: Promise<void> | undefined
    act(() => {
      refetchPromise = result.current.refetch()
    })
    // The previous data stays on screen while the new request is in flight.
    expect(result.current.loading).toBe(false)
    expect(result.current.refetching).toBe(true)
    expect(result.current.data).toBe('first')

    await act(async () => calls[1]?.resolve('second'))
    await refetchPromise
    expect(result.current.refetching).toBe(false)
    expect(result.current.data).toBe('second')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('resolves the refetch promise only after the new request settles', async () => {
    const calls: Deferred<number>[] = []
    const fetcher = vi.fn(() => {
      const next = deferred<number>()
      calls.push(next)
      return next.promise
    })
    const { result } = renderHook(() => useQuery(fetcher, []))
    await act(async () => calls[0]?.resolve(1))

    let settled = false
    let refetchPromise!: Promise<void>
    act(() => {
      refetchPromise = result.current.refetch().then(() => {
        settled = true
      })
    })
    await act(async () => {
      await Promise.resolve()
    })
    expect(settled).toBe(false)

    await act(async () => calls[1]?.resolve(2))
    await refetchPromise
    expect(settled).toBe(true)
    expect(result.current.data).toBe(2)
  })

  it('settles the refetch promise even when the new request fails', async () => {
    const calls: Deferred<number>[] = []
    const fetcher = vi.fn(() => {
      const next = deferred<number>()
      calls.push(next)
      return next.promise
    })
    const { result } = renderHook(() => useQuery(fetcher, []))
    await act(async () => calls[0]?.resolve(1))

    let refetchPromise!: Promise<void>
    act(() => {
      refetchPromise = result.current.refetch()
    })
    await act(async () => calls[1]?.reject(new Error('boom')))
    await expect(refetchPromise).resolves.toBeUndefined()
    expect(result.current.error).toBe('boom')
    expect(result.current.refetching).toBe(false)
  })

  it('treats a deps change as a fresh load (loading = true) even with previous data', async () => {
    const calls: Deferred<string>[] = []
    const fetcher = vi.fn(() => {
      const next = deferred<string>()
      calls.push(next)
      return next.promise
    })
    const { result, rerender } = renderHook(({ id }: { id: number }) => useQuery(fetcher, [id]), { initialProps: { id: 1 } })
    await act(async () => calls[0]?.resolve('one'))
    expect(result.current.data).toBe('one')

    rerender({ id: 2 })
    expect(result.current.loading).toBe(true)
    expect(result.current.refetching).toBe(false)
    await act(async () => calls[1]?.resolve('two'))
    expect(result.current.loading).toBe(false)
    expect(result.current.data).toBe('two')
  })

  it('keeps QueryState children mounted across a refetch', async () => {
    const calls: Deferred<string>[] = []
    const fetcher = vi.fn(() => {
      const next = deferred<string>()
      calls.push(next)
      return next.promise
    })
    let refetch: (() => Promise<void>) | undefined

    function Page() {
      const query = useQuery(fetcher, [])
      useEffect(() => {
        refetch = query.refetch
      }, [query.refetch])
      return (
        <QueryState loading={query.loading} error={query.error}>
          <p>Contenido: {query.data}</p>
        </QueryState>
      )
    }

    render(<Page />)
    expect(screen.queryByText(/Contenido/)).not.toBeInTheDocument()
    await act(async () => calls[0]?.resolve('a'))
    await waitFor(() => expect(screen.getByText('Contenido: a')).toBeInTheDocument())

    let pending: Promise<void> | undefined
    act(() => {
      pending = refetch?.()
    })
    // Children stay mounted with the previous data while the refetch is in flight.
    expect(screen.getByText('Contenido: a')).toBeInTheDocument()
    await act(async () => calls[1]?.resolve('b'))
    await pending
    expect(screen.getByText('Contenido: b')).toBeInTheDocument()
  })
})

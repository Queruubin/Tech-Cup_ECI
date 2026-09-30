import { useCallback, useEffect, useRef, useState } from 'react'
import { errorMessage, isApiError } from '@/lib/api'

export interface QueryResult<T> {
  data: T | null
  /** True while the first load (or a load for new deps) is in flight and there is no data to show. */
  loading: boolean
  /** True while `refetch()` is reloading data that is already displayed; `loading` stays false. */
  refetching: boolean
  error: string | null
  /** HTTP status of the last error, if any (useful to treat 404 as "empty"). */
  errorStatus: number | null
  /** Re-runs the fetcher. The promise settles once the new request has finished (never rejects). */
  refetch: () => Promise<void>
  /** Replaces the cached data locally (e.g. after a mutation returned the new entity). */
  setData: (updater: T | null | ((previous: T | null) => T | null)) => void
}

export interface QueryOptions {
  /** When false the query is not executed (e.g. missing id or unauthorised). */
  enabled?: boolean
  /** HTTP statuses that should resolve to `data = null` instead of an error (e.g. 404 for "mine"). */
  nullOnStatus?: number[]
}

function sameDeps(a: readonly unknown[] | null, b: readonly unknown[]): boolean {
  if (a === null || a.length !== b.length) return false
  return a.every((value, index) => Object.is(value, b[index]))
}

/**
 * Minimal server-state hook: runs `fetcher` on mount and whenever `deps` change,
 * aborting in-flight requests on cleanup. Not a cache; pages own their data.
 *
 * A `refetch()` with data already loaded keeps that data on screen (`refetching = true`)
 * instead of flipping `loading`, so consumers such as `QueryState` do not unmount their children.
 */
export function useQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: readonly unknown[],
  options: QueryOptions = {},
): QueryResult<T> {
  const { enabled = true, nullOnStatus = [] } = options
  const [data, setDataState] = useState<T | null>(null)
  const [loading, setLoading] = useState<boolean>(enabled)
  const [refetching, setRefetching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [errorStatus, setErrorStatus] = useState<number | null>(null)
  const [version, setVersion] = useState(0)
  const fetcherRef = useRef(fetcher)
  useEffect(() => {
    fetcherRef.current = fetcher
  })
  const nullOnStatusKey = nullOnStatus.join(',')

  // Mirrors of the latest data / deps so the effect can tell a refetch from a fresh load.
  const dataRef = useRef<T | null>(null)
  const lastDepsRef = useRef<readonly unknown[] | null>(null)
  // Callers awaiting `refetch()`; resolved once the request that follows the version bump settles.
  const waitersRef = useRef<Array<() => void>>([])

  const setData = useCallback((updater: T | null | ((previous: T | null) => T | null)) => {
    setDataState((previous) => {
      const next = typeof updater === 'function' ? (updater as (previous: T | null) => T | null)(previous) : updater
      dataRef.current = next
      return next
    })
  }, [])

  const settleWaiters = () => {
    const waiters = waitersRef.current
    waitersRef.current = []
    for (const resolve of waiters) resolve()
  }

  useEffect(() => {
    const currentDeps = [enabled, nullOnStatusKey, ...deps]
    const isRefetch = sameDeps(lastDepsRef.current, currentDeps)
    lastDepsRef.current = currentDeps

    if (!enabled) {
      setLoading(false)
      setRefetching(false)
      settleWaiters()
      return
    }
    const controller = new AbortController()
    if (isRefetch && dataRef.current !== null) {
      setRefetching(true)
    } else {
      setLoading(true)
    }
    setError(null)
    setErrorStatus(null)
    const silentStatuses = nullOnStatusKey ? nullOnStatusKey.split(',').map(Number) : []

    const finish = () => {
      setLoading(false)
      setRefetching(false)
      settleWaiters()
    }

    fetcherRef
      .current(controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return
        dataRef.current = result
        setDataState(result)
        finish()
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return
        if (isApiError(cause) && silentStatuses.includes(cause.status)) {
          dataRef.current = null
          setDataState(null)
          finish()
          return
        }
        setError(errorMessage(cause))
        setErrorStatus(isApiError(cause) ? cause.status : null)
        finish()
      })

    // An aborted request is superseded by the next effect run, which settles the waiters itself.
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, version, nullOnStatusKey, ...deps])

  // Settle any pending waiters if the hook unmounts mid-flight.
  useEffect(() => () => settleWaiters(), [])

  const refetch = useCallback(
    () =>
      new Promise<void>((resolve) => {
        waitersRef.current.push(resolve)
        setVersion((v) => v + 1)
      }),
    [],
  )

  return { data, loading, refetching, error, errorStatus, refetch, setData }
}

export interface MutationResult<Args extends unknown[], T> {
  mutate: (...args: Args) => Promise<T>
  loading: boolean
  error: string | null
  /** Field-level errors from the last failed call (`details[]` of the API error). */
  fieldErrors: Record<string, string>
  reset: () => void
}

/** Wraps an async action with loading / error state. Rethrows so callers can react. */
export function useMutation<Args extends unknown[], T>(
  action: (...args: Args) => Promise<T>,
): MutationResult<Args, T> {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const actionRef = useRef(action)
  useEffect(() => {
    actionRef.current = action
  })

  const mutate = useCallback(async (...args: Args): Promise<T> => {
    setLoading(true)
    setError(null)
    setFieldErrors({})
    try {
      return await actionRef.current(...args)
    } catch (cause) {
      setError(errorMessage(cause))
      if (isApiError(cause)) setFieldErrors(cause.fieldErrors)
      throw cause
    } finally {
      setLoading(false)
    }
  }, [])

  const reset = useCallback(() => {
    setError(null)
    setFieldErrors({})
  }, [])

  return { mutate, loading, error, fieldErrors, reset }
}

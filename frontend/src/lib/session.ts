/**
 * One-shot "session expired" marker handed from the API client (401 handling) to the login page.
 * Lives in `sessionStorage` so it survives the hard redirect but not a new tab.
 */
export const SESSION_EXPIRED_KEY = 'techcup.sessionExpired'
export const SESSION_RETURN_TO_KEY = 'techcup.sessionReturnTo'

export interface ExpiredSession {
  expired: boolean
  /** `pathname + search` the user was on when the session expired, if it is a safe in-app path. */
  returnTo: string | null
}

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null
  } catch {
    return null
  }
}

/** Only relative in-app paths are accepted as a return target (no protocol-relative or absolute URLs). */
export function isSafeReturnPath(path: string | null | undefined): path is string {
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') && path !== '/login'
}

export function markSessionExpired(returnTo?: string | null): void {
  const store = storage()
  if (!store) return
  try {
    store.setItem(SESSION_EXPIRED_KEY, '1')
    if (isSafeReturnPath(returnTo)) store.setItem(SESSION_RETURN_TO_KEY, returnTo)
    else store.removeItem(SESSION_RETURN_TO_KEY)
  } catch {
    // Storage may be unavailable (private mode / quota); the redirect still happens.
  }
}

/**
 * Reads the marker without clearing it. Safe to call from a `useState` initializer: React
 * StrictMode invokes initializers more than once in development, so a read-and-clear there would
 * hand the second invocation an empty store and the alert would never show.
 */
export function peekSessionExpired(): ExpiredSession {
  const store = storage()
  if (!store) return { expired: false, returnTo: null }
  try {
    const expired = store.getItem(SESSION_EXPIRED_KEY) === '1'
    const storedReturnTo = store.getItem(SESSION_RETURN_TO_KEY)
    return { expired, returnTo: isSafeReturnPath(storedReturnTo) ? storedReturnTo : null }
  } catch {
    return { expired: false, returnTo: null }
  }
}

/** Clears the marker (idempotent). Call it from an effect once the value has been captured. */
export function clearSessionExpired(): void {
  const store = storage()
  if (!store) return
  try {
    store.removeItem(SESSION_EXPIRED_KEY)
    store.removeItem(SESSION_RETURN_TO_KEY)
  } catch {
    /* storage unavailable: nothing to clear */
  }
}

/** Reads and clears the marker in one step. Returns `{ expired: false }` when nothing was stored. */
export function consumeSessionExpired(): ExpiredSession {
  const value = peekSessionExpired()
  clearSessionExpired()
  return value
}

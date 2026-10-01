import { isApiError } from '@/lib/api'
import { toast } from '@/store/ui.store'

export const UNHANDLED_ERROR_MESSAGE = 'Ocurrió un error inesperado. Intente de nuevo.'

function isAbort(reason: unknown): boolean {
  return reason instanceof DOMException && reason.name === 'AbortError'
}

/**
 * Turns a promise rejection nobody handled into a user-visible toast. API errors keep their
 * (already user-facing) message, which includes the trace reference for server faults; anything
 * else gets a generic message so technical text never reaches the user. Aborted requests are ignored.
 * Returns the toast message, or `null` when nothing was shown.
 */
export function reportUnhandledRejection(reason: unknown, isDev: boolean = import.meta.env.DEV): string | null {
  if (isAbort(reason)) return null
  if (isDev) console.error('Unhandled promise rejection', reason)
  const message = isApiError(reason) && reason.message ? reason.message : UNHANDLED_ERROR_MESSAGE
  toast.error(message)
  return message
}

/** Installs the global `unhandledrejection` listener once; returns a function that removes it. */
export function installUnhandledRejectionHandler(target: Window = window): () => void {
  const listener = (event: PromiseRejectionEvent) => {
    // The toast (and the DEV log above) replace the browser's default console report.
    event.preventDefault()
    reportUnhandledRejection(event.reason)
  }
  target.addEventListener('unhandledrejection', listener)
  return () => target.removeEventListener('unhandledrejection', listener)
}

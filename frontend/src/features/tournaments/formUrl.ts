import type { TournamentStatus } from '@/types/api'

/**
 * Rule of the university-form link of a tournament, mirroring the server's `FormUrls`: required,
 * at most 500 characters, an absolute `http://` or `https://` URL with a host and no spaces.
 * The link is rendered as an anchor, so anything else (`javascript:`, `data:`, relative paths…)
 * is refused here and never rendered as a link.
 */
export const FORM_URL_MAX_LENGTH = 500
export const FORM_URL_REQUIRED_MESSAGE = 'Ingrese la URL del formulario de la Escuela.'
export const FORM_URL_TOO_LONG_MESSAGE = `La URL del formulario no puede superar los ${FORM_URL_MAX_LENGTH} caracteres.`
export const FORM_URL_INVALID_MESSAGE = 'Ingrese una URL válida que empiece por http:// o https://.'

// Scheme followed by "//" and at least one authority character: rejects "http:host" and "https:///path",
// which the browser's URL parser would otherwise repair into a valid-looking address.
const ABSOLUTE_HTTP = /^https?:\/\/[^/?#\s]/i

function isAbsoluteHttpUrl(url: string): boolean {
  if (!ABSOLUTE_HTTP.test(url) || /\s/.test(url)) return false
  try {
    const parsed = new URL(url)
    return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && parsed.hostname !== ''
  } catch {
    return false
  }
}

/** The validation message for what the organizer typed, or `null` when it may be sent. */
export function formUrlError(raw: string): string | null {
  const url = raw.trim()
  if (url === '') return FORM_URL_REQUIRED_MESSAGE
  if (url.length > FORM_URL_MAX_LENGTH) return FORM_URL_TOO_LONG_MESSAGE
  return isAbsoluteHttpUrl(url) ? null : FORM_URL_INVALID_MESSAGE
}

/**
 * The link to render for a URL that came from the server, or `null` when there is none or it does
 * not pass the rule above (defence in depth: a bad value is never rendered as a link).
 */
export function safeFormUrl(url: string | null | undefined): string | null {
  if (!url) return null
  return formUrlError(url) === null ? url.trim() : null
}

/** Same statuses as the server: never while SUSPENDED (frozen) nor once FINISHED (history). */
export function canEditFormUrl(status: TournamentStatus): boolean {
  return status === 'DRAFT' || status === 'ACTIVE' || status === 'IN_PROGRESS'
}

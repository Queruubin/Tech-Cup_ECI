import type { ApiErrorBody, ApiErrorDetail } from '@/types/api'

/** Base URL of the REST API. Defaults to the relative `/api` prefix (proxied by Vite in dev). */
export const API_BASE_URL: string = (import.meta.env.VITE_API_URL as string | undefined)?.trim() || '/api'

/**
 * Normalised API error. Mirrors the backend `ApiError` body
 * `{ status, error, message, path, details?: [{ field, message }] }`.
 */
export class ApiError extends Error {
  readonly status: number
  readonly error: string
  readonly path: string | undefined
  readonly details: ApiErrorDetail[]

  constructor(body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = body.status
    this.error = body.error
    this.path = body.path
    this.details = body.details ?? []
  }

  /** `{ field: message }` map, convenient for showing errors next to form fields. */
  get fieldErrors(): Record<string, string> {
    const map: Record<string, string> = {}
    for (const detail of this.details) {
      if (!(detail.field in map)) map[detail.field] = detail.message
    }
    return map
  }

  get isValidation(): boolean {
    return this.status === 400 && this.details.length > 0
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}

/** Extracts a human-readable message from any thrown value. */
export function errorMessage(value: unknown, fallback = 'Ocurrió un error inesperado.'): string {
  if (isApiError(value)) return value.message || fallback
  if (value instanceof Error) return value.message || fallback
  return fallback
}

// ---------------------------------------------------------------------------
// Session wiring (set by the auth store; keeps this module free of store imports)
// ---------------------------------------------------------------------------

interface SessionHandlers {
  getToken: () => string | null
  onUnauthorized: () => void
}

let sessionHandlers: SessionHandlers = {
  getToken: () => null,
  onUnauthorized: () => {},
}

export function setApiSessionHandlers(handlers: SessionHandlers): void {
  sessionHandlers = handlers
}

// ---------------------------------------------------------------------------
// Request helpers
// ---------------------------------------------------------------------------

type QueryValue = string | number | boolean | null | undefined
export type Query = Record<string, QueryValue>

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** JSON body. Ignored when `formData` is given. */
  body?: unknown
  /** Multipart body. Sent as-is, without a Content-Type header (the browser sets the boundary). */
  formData?: FormData
  query?: Query
  signal?: AbortSignal
  /** When true, a 401 response does not clear the session (used by the login call itself). */
  skipAuthRedirect?: boolean
}

function buildUrl(path: string, query?: Query): string {
  const normalisedPath = path.startsWith('/') ? path : `/${path}`
  let url = `${API_BASE_URL}${normalisedPath}`
  if (query) {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') continue
      params.set(key, String(value))
    }
    const qs = params.toString()
    if (qs) url += `${url.includes('?') ? '&' : '?'}${qs}`
  }
  return url
}

/** Turns any response into an `ApiError`, tolerating non-JSON bodies (e.g. proxy errors). */
export async function normalizeErrorResponse(response: Response): Promise<ApiError> {
  const fallback: ApiErrorBody = {
    status: response.status,
    error: response.statusText || 'Error',
    message: defaultMessageFor(response.status),
    path: undefined,
  }
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) return new ApiError(fallback)
  try {
    const raw: unknown = await response.json()
    if (raw && typeof raw === 'object') {
      const body = raw as Partial<ApiErrorBody>
      return new ApiError({
        status: typeof body.status === 'number' ? body.status : response.status,
        error: typeof body.error === 'string' ? body.error : fallback.error,
        message: typeof body.message === 'string' && body.message ? body.message : fallback.message,
        path: typeof body.path === 'string' ? body.path : undefined,
        details: Array.isArray(body.details) ? body.details.filter(isDetail) : [],
        timestamp: typeof body.timestamp === 'string' ? body.timestamp : undefined,
      })
    }
  } catch {
    // fall through to the generic error
  }
  return new ApiError(fallback)
}

function isDetail(value: unknown): value is ApiErrorDetail {
  return (
    !!value &&
    typeof value === 'object' &&
    typeof (value as ApiErrorDetail).field === 'string' &&
    typeof (value as ApiErrorDetail).message === 'string'
  )
}

function defaultMessageFor(status: number): string {
  switch (status) {
    case 400:
      return 'La solicitud contiene datos inválidos.'
    case 401:
      return 'Su sesión ha expirado. Inicie sesión nuevamente.'
    case 403:
      return 'No tiene permiso para realizar esta acción.'
    case 404:
      return 'El recurso solicitado no existe.'
    case 409:
      return 'La operación entra en conflicto con el estado actual.'
    case 413:
      return 'El archivo supera el tamaño máximo permitido (5 MB).'
    case 415:
      return 'Tipo de archivo no permitido.'
    case 429:
      return 'Demasiados intentos. Espere unos minutos e intente de nuevo.'
    case 0:
      return 'No fue posible conectar con el servidor.'
    default:
      return status >= 500 ? 'Error interno del servidor. Intente más tarde.' : 'Ocurrió un error inesperado.'
  }
}

/**
 * Core fetch wrapper. Adds the bearer token, serialises JSON, normalises errors and
 * clears the session on 401.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, formData, query, signal, skipAuthRedirect = false } = options
  const headers = new Headers({ Accept: 'application/json' })
  const token = sessionHandlers.getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let payload: BodyInit | undefined
  if (formData) {
    payload = formData
  } else if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    payload = JSON.stringify(body)
  }

  let response: Response
  try {
    response = await fetch(buildUrl(path, query), { method, headers, body: payload, signal })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError({ status: 0, error: 'NetworkError', message: defaultMessageFor(0) })
  }

  if (!response.ok) {
    const error = await normalizeErrorResponse(response)
    if (response.status === 401 && !skipAuthRedirect) sessionHandlers.onUnauthorized()
    throw error
  }

  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return undefined as T
  }
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>(path, { query, signal }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, query?: Query) => request<T>(path, { method: 'DELETE', query }),
  /** Multipart upload. `fields` are appended as plain form values; `file` under the given field name. */
  upload: <T>(
    path: string,
    file: File | null,
    fields: Record<string, string> = {},
    fileField = 'file',
    method: 'POST' | 'PUT' = 'POST',
  ) => {
    const formData = new FormData()
    for (const [key, value] of Object.entries(fields)) formData.append(key, value)
    if (file) formData.append(fileField, file)
    return request<T>(path, { method, formData })
  },
}

/** Authenticated binary fetch, e.g. for `GET /files/{id}`. */
export async function fetchBlob(path: string, signal?: AbortSignal): Promise<Blob> {
  const headers = new Headers()
  const token = sessionHandlers.getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  let response: Response
  try {
    response = await fetch(buildUrl(path), { headers, signal })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError({ status: 0, error: 'NetworkError', message: defaultMessageFor(0) })
  }
  if (!response.ok) {
    const error = await normalizeErrorResponse(response)
    if (response.status === 401) sessionHandlers.onUnauthorized()
    throw error
  }
  return response.blob()
}

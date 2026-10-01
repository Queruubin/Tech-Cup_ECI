import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  NETWORK_ERROR_MESSAGE,
  api,
  errorMessage,
  isApiError,
  normalizeErrorResponse,
  request,
  setApiSessionHandlers,
  withTraceReference,
} from './api'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('api error normalization', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    setApiSessionHandlers({ getToken: () => null, onUnauthorized: () => {} })
  })

  it('maps the backend ApiError body into an ApiError instance', async () => {
    const response = jsonResponse(
      {
        timestamp: '2025-01-01T00:00:00Z',
        status: 400,
        error: 'Bad Request',
        message: 'Datos inválidos',
        path: '/api/auth/register',
        details: [
          { field: 'email', message: 'Debe ser institucional' },
          { field: 'semester', message: 'Obligatorio para estudiantes' },
        ],
      },
      400,
    )
    const error = await normalizeErrorResponse(response)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.error).toBe('Bad Request')
    expect(error.message).toBe('Datos inválidos')
    expect(error.path).toBe('/api/auth/register')
    expect(error.details).toHaveLength(2)
    expect(error.fieldErrors).toEqual({ email: 'Debe ser institucional', semester: 'Obligatorio para estudiantes' })
    expect(error.isValidation).toBe(true)
  })

  it('falls back to a generic message for non-JSON error bodies', async () => {
    const response = new Response('<html>Bad Gateway</html>', {
      status: 502,
      statusText: 'Bad Gateway',
      headers: { 'Content-Type': 'text/html' },
    })
    const error = await normalizeErrorResponse(response)
    expect(error.status).toBe(502)
    expect(error.details).toEqual([])
    expect(error.message).toMatch(/servidor/i)
  })

  it.each([
    [403, /no tiene permiso para realizar esta acción/i],
    [413, /supera el tamaño máximo permitido \(5 MB\)/i],
    [415, /tipo de archivo no permitido/i],
    [429, /demasiados intentos/i],
  ])('maps a non-JSON %i response to its default Spanish message', async (status, expected) => {
    const response = new Response('', { status, headers: { 'Content-Type': 'text/plain' } })
    const error = await normalizeErrorResponse(response)
    expect(error.status).toBe(status)
    expect(error.message).toMatch(expected)
  })

  it('keeps the server-provided message when the error body is JSON', async () => {
    const response = jsonResponse({ status: 429, error: 'Too Many Requests', message: 'Cuenta bloqueada por 15 minutos' }, 429)
    const error = await normalizeErrorResponse(response)
    expect(error.status).toBe(429)
    expect(error.message).toBe('Cuenta bloqueada por 15 minutos')
  })

  it('ignores malformed detail entries', async () => {
    const response = jsonResponse({ status: 400, error: 'Bad Request', message: 'x', details: [{ foo: 1 }, { field: 'a', message: 'b' }] }, 400)
    const error = await normalizeErrorResponse(response)
    expect(error.details).toEqual([{ field: 'a', message: 'b' }])
  })

  it('request() throws the normalized error and sends the bearer token', async () => {
    setApiSessionHandlers({ getToken: () => 'abc', onUnauthorized: () => {} })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ status: 409, error: 'Conflict', message: 'El equipo ya existe', path: '/api/teams' }, 409),
    )
    await expect(api.post('/teams', { name: 'x' })).rejects.toMatchObject({ status: 409, message: 'El equipo ya existe' })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/teams')
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer abc')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init.body).toBe(JSON.stringify({ name: 'x' }))
  })

  it('request() calls onUnauthorized on 401 unless skipAuthRedirect is set', async () => {
    const onUnauthorized = vi.fn()
    setApiSessionHandlers({ getToken: () => 'abc', onUnauthorized })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ status: 401, error: 'Unauthorized', message: 'Expirado' }, 401))

    await expect(request('/auth/me')).rejects.toBeInstanceOf(ApiError)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)

    await expect(request('/auth/login', { method: 'POST', body: {}, skipAuthRedirect: true })).rejects.toBeInstanceOf(ApiError)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('request() resolves undefined for 204 responses and parses JSON otherwise', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(jsonResponse({ id: 1 }))
    await expect(request<void>('/auth/logout', { method: 'POST' })).resolves.toBeUndefined()
    await expect(request<{ id: number }>('/teams/1')).resolves.toEqual({ id: 1 })
  })

  it('request() serialises query parameters and skips empty values', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse([]))
    await api.get('/players', { position: 'FORWARD', available: true, search: '', other: undefined })
    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toBe('/api/players?position=FORWARD&available=true')
  })

  it('wraps network failures as ApiError with status 0', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    try {
      await request('/home')
      expect.unreachable('should have thrown')
    } catch (cause) {
      expect(isApiError(cause)).toBe(true)
      expect((cause as ApiError).status).toBe(0)
    }
  })
})

describe('api trace ids', () => {
  afterEach(() => vi.restoreAllMocks())

  it('captures the body traceId and quotes it in 5xx messages', async () => {
    const response = jsonResponse({ status: 500, error: 'Internal Server Error', message: 'Error interno del servidor.', traceId: 'abc123' }, 500)
    const error = await normalizeErrorResponse(response)
    expect(error.traceId).toBe('abc123')
    expect(error.message).toBe('Error interno del servidor. Código de referencia: abc123')
    expect(errorMessage(error)).toContain('Código de referencia: abc123')
  })

  it('falls back to the X-Trace-Id header (non-JSON proxy error)', async () => {
    const response = new Response('<html>Bad Gateway</html>', {
      status: 502,
      statusText: 'Bad Gateway',
      headers: { 'Content-Type': 'text/html', 'X-Trace-Id': 'hdr-9' },
    })
    const error = await normalizeErrorResponse(response)
    expect(error.traceId).toBe('hdr-9')
    expect(error.message).toMatch(/servidor.*Código de referencia: hdr-9$/)
  })

  it('prefers the body traceId over the header', async () => {
    const response = new Response(JSON.stringify({ status: 503, error: 'x', message: 'Caído', traceId: 'body-1' }), {
      status: 503,
      headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'hdr-1' },
    })
    expect((await normalizeErrorResponse(response)).message).toBe('Caído. Código de referencia: body-1')
  })

  it('keeps expected business errors (e.g. 409) free of the reference but still exposes the id', async () => {
    const response = jsonResponse({ status: 409, error: 'Conflict', message: 'Reabra primero el partido siguiente.', traceId: 't-409' }, 409)
    const error = await normalizeErrorResponse(response)
    expect(error.message).toBe('Reabra primero el partido siguiente.')
    expect(error.traceId).toBe('t-409')
  })

  it('withTraceReference leaves messages unchanged without a trace id', () => {
    expect(withTraceReference('Error interno.', 500, undefined)).toBe('Error interno.')
    expect(withTraceReference('Error interno.', 418, 'z')).toBe('Error interno. Código de referencia: z')
  })

  it('uses the connection message when fetch rejects', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(request('/home')).rejects.toMatchObject({ status: 0, message: NETWORK_ERROR_MESSAGE })
    expect(NETWORK_ERROR_MESSAGE).toBe(
      'No fue posible conectar con el servidor. Verifique su conexión e intente de nuevo.',
    )
  })
})

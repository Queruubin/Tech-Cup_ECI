/** Copy and helpers shared by the route error page and the top-level error boundary. */

export const GENERIC_ERROR_COPY = {
  title: 'Algo salió mal',
  description: 'Ocurrió un error inesperado al mostrar esta página. Intente de nuevo o vuelva al inicio.',
} as const

export const NOT_FOUND_COPY = {
  title: 'Página no encontrada',
  description: 'La dirección que ingresó no existe o fue movida.',
} as const

/** Technical message of any thrown value, exposed only in development builds. */
export function devErrorDetails(error: unknown, isDev: boolean = import.meta.env.DEV): string | null {
  if (!isDev || error === undefined || error === null) return null
  if (error instanceof Error) return error.message || error.name
  if (typeof error === 'string') return error
  try {
    return JSON.stringify(error)
  } catch {
    return String(error)
  }
}

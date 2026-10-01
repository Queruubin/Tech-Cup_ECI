import { isRouteErrorResponse, useRouteError } from 'react-router'
import { GENERIC_ERROR_COPY, NOT_FOUND_COPY, devErrorDetails } from './errorCopy'
import { ErrorFallback } from './ErrorFallback'

/** Root `errorElement`: 404 route responses read "Página no encontrada", anything else "Algo salió mal". */
export function RouteErrorPage() {
  const error = useRouteError()

  if (isRouteErrorResponse(error) && error.status === 404) {
    return <ErrorFallback {...NOT_FOUND_COPY} showRetry={false} />
  }

  const details = isRouteErrorResponse(error)
    ? devErrorDetails(`${error.status} ${error.statusText}${typeof error.data === 'string' && error.data ? `: ${error.data}` : ''}`)
    : devErrorDetails(error)
  return <ErrorFallback {...GENERIC_ERROR_COPY} details={details} />
}

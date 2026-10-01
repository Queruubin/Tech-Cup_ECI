import { Component, type ErrorInfo, type ReactNode } from 'react'
import { GENERIC_ERROR_COPY, devErrorDetails } from './errorCopy'
import { ErrorFallback } from './ErrorFallback'

interface AppErrorBoundaryProps {
  children: ReactNode
}

interface AppErrorBoundaryState {
  error: unknown
  hasError: boolean
}

/**
 * Last line of defence for render errors thrown outside the router (providers, session bootstrap).
 * Errors inside routes are handled by the root route's `errorElement`.
 */
export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { error: null, hasError: false }

  static getDerivedStateFromError(error: unknown): AppErrorBoundaryState {
    return { error, hasError: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    if (import.meta.env.DEV) console.error('Unhandled render error', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return <ErrorFallback {...GENERIC_ERROR_COPY} details={devErrorDetails(this.state.error)} />
    }
    return this.props.children
  }
}

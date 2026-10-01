import { RouterProvider } from 'react-router'
import { AppErrorBoundary } from './AppErrorBoundary'
import { Providers } from './providers'
import { router } from './router'

export function App() {
  return (
    <AppErrorBoundary>
      <Providers>
        <RouterProvider router={router} />
      </Providers>
    </AppErrorBoundary>
  )
}

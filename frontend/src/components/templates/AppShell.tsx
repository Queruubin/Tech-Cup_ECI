import type { ReactNode } from 'react'
import { ToastViewport } from '@/components/molecules/ToastViewport'

export interface AppShellProps {
  navbar: ReactNode
  /** Optional page-wide notice rendered below the navbar, aligned with the content (e.g. a warning banner). */
  banner?: ReactNode
  children: ReactNode
}

/** Authenticated layout: navbar + optional banner + constrained content area. */
export function AppShell({ navbar, banner, children }: AppShellProps) {
  return (
    <div className="flex min-h-screen flex-col bg-stone-50">
      {navbar}
      {banner && <div className="mx-auto w-full max-w-6xl px-4 pt-4 sm:px-6 sm:pt-6">{banner}</div>}
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6 sm:py-8">{children}</main>
      <footer className="border-t border-stone-200 py-4 text-center text-xs text-stone-400">
        TechCup Fútbol · Escuela Colombiana de Ingeniería
      </footer>
      <ToastViewport />
    </div>
  )
}

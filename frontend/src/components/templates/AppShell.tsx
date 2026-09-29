import type { ReactNode } from 'react'
import { ToastViewport } from '@/components/molecules/ToastViewport'

export interface AppShellProps {
  navbar: ReactNode
  children: ReactNode
}

/** Authenticated layout: navbar + constrained content area. */
export function AppShell({ navbar, children }: AppShellProps) {
  return (
    <div className="flex min-h-screen flex-col bg-stone-50">
      {navbar}
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6 sm:py-8">{children}</main>
      <footer className="border-t border-stone-200 py-4 text-center text-xs text-stone-400">
        TechCup Fútbol · Escuela Colombiana de Ingeniería
      </footer>
      <ToastViewport />
    </div>
  )
}

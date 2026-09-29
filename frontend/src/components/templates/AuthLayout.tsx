import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { ToastViewport } from '@/components/molecules/ToastViewport'

export interface AuthLayoutProps {
  title: string
  subtitle?: ReactNode
  children: ReactNode
  /** Rendered below the card (e.g. "¿No tiene cuenta?"). */
  footer?: ReactNode
  wide?: boolean
}

/** Public layout for login / register. */
export function AuthLayout({ title, subtitle, children, footer, wide }: AuthLayoutProps) {
  return (
    <div className="flex min-h-screen flex-col bg-stone-50">
      <div className="flex flex-1 flex-col items-center justify-center px-4 py-10">
        <Link to="/" className="mb-6 flex flex-col items-center gap-3 text-ink">
          <img src="/logo.svg" alt="" className="h-24 w-auto" />
          <span className="text-xl font-semibold tracking-tight">
            TechCup <span className="text-brand-600">Fútbol</span>
          </span>
        </Link>
        <div className={`w-full rounded-2xl border border-stone-200 bg-white p-6 shadow-sm sm:p-8 ${wide ? 'max-w-2xl' : 'max-w-md'}`}>
          <h1 className="text-xl font-semibold text-ink">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-stone-500">{subtitle}</p>}
          <div className="mt-6">{children}</div>
        </div>
        {footer && <div className="mt-4 text-sm text-stone-600">{footer}</div>}
      </div>
      <ToastViewport />
    </div>
  )
}

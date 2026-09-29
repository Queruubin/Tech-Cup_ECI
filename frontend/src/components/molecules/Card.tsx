import type { HTMLAttributes, ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface CardProps extends Omit<HTMLAttributes<HTMLElement>, 'title'> {
  title?: ReactNode
  description?: ReactNode
  /** Rendered on the right side of the header (e.g. actions or a badge). */
  actions?: ReactNode
  footer?: ReactNode
  padded?: boolean
}

export function Card({ title, description, actions, footer, padded = true, className, children, ...rest }: CardProps) {
  const hasHeader = title || description || actions
  return (
    <section
      className={cn('rounded-2xl border border-stone-200 bg-white shadow-sm', className)}
      {...rest}
    >
      {hasHeader && (
        <header className="flex items-start justify-between gap-3 border-b border-stone-100 px-5 py-4">
          <div className="min-w-0">
            {title && <h2 className="text-base font-semibold text-ink">{title}</h2>}
            {description && <p className="mt-0.5 text-sm text-stone-500">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cn(padded && 'px-5 py-4')}>{children}</div>
      {footer && <footer className="border-t border-stone-100 px-5 py-3">{footer}</footer>}
    </section>
  )
}

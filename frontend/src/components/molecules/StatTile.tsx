import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface StatTileProps {
  label: string
  value: ReactNode
  hint?: ReactNode
  className?: string
}

export function StatTile({ label, value, hint, className }: StatTileProps) {
  return (
    <div className={cn('rounded-2xl border border-stone-200 bg-white px-4 py-3 shadow-sm', className)}>
      <p className="text-xs font-medium uppercase tracking-wide text-stone-500">{label}</p>
      <p className="mt-1 text-2xl font-semibold text-ink">{value}</p>
      {hint && <p className="mt-0.5 text-xs text-stone-500">{hint}</p>}
    </div>
  )
}

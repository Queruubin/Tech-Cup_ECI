import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface TabItem<Id extends string = string> {
  id: Id
  label: ReactNode
  /** Small counter or badge rendered after the label. */
  badge?: ReactNode
}

export interface TabsProps<Id extends string = string> {
  tabs: TabItem<Id>[]
  active: Id
  onChange: (id: Id) => void
  className?: string
}

export function Tabs<Id extends string>({ tabs, active, onChange, className }: TabsProps<Id>) {
  return (
    <div className={cn('overflow-x-auto border-b border-stone-200', className)} role="tablist">
      <div className="flex min-w-max gap-1">
        {tabs.map((tab) => {
          const selected = tab.id === active
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => onChange(tab.id)}
              className={cn(
                '-mb-px flex items-center gap-1.5 whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition-colors',
                selected
                  ? 'border-brand-600 text-brand-700'
                  : 'border-transparent text-stone-500 hover:border-stone-300 hover:text-stone-800',
              )}
            >
              {tab.label}
              {tab.badge !== undefined && tab.badge !== null && (
                <span className="rounded-full bg-stone-100 px-1.5 text-xs text-stone-600">{tab.badge}</span>
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}

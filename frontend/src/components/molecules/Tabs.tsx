import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface TabItem<Id extends string = string> {
  id: Id
  label: ReactNode
  /** Small counter or badge rendered after the label. */
  badge?: ReactNode
  /** Subtle tinted background (idle and active) that sets a special tab apart, e.g. "Gestión". */
  highlight?: boolean
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
              data-highlight={tab.highlight || undefined}
              className={cn(
                '-mb-px flex items-center gap-1.5 whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition-colors',
                selected
                  ? 'border-brand-600 text-brand-700'
                  : cn(
                      'border-transparent hover:border-stone-300 hover:text-stone-800',
                      // On the tint the idle text is one step darker so it keeps AA contrast.
                      tab.highlight ? 'text-stone-600' : 'text-stone-500',
                    ),
                // Tinted in both states, so the tab stands apart without competing with the active one.
                tab.highlight && 'rounded-t-lg bg-brand-50 hover:bg-brand-100/70',
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

import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export interface Column<Row> {
  key: string
  header: ReactNode
  /** Renders the cell. */
  cell: (row: Row) => ReactNode
  align?: 'left' | 'center' | 'right'
  className?: string
  /** Hide on small screens. */
  hideOnMobile?: boolean
}

export interface TableProps<Row> {
  columns: Column<Row>[]
  rows: Row[]
  rowKey: (row: Row) => string | number
  /** Shown when `rows` is empty. */
  empty?: ReactNode
  className?: string
  rowClassName?: (row: Row) => string | undefined
  dense?: boolean
}

const ALIGN = { left: 'text-left', center: 'text-center', right: 'text-right' } as const

export function Table<Row>({ columns, rows, rowKey, empty, className, rowClassName, dense }: TableProps<Row>) {
  return (
    <div className={cn('overflow-x-auto rounded-2xl border border-stone-200 bg-white', className)}>
      <table className="min-w-full divide-y divide-stone-200 text-sm">
        <thead className="bg-stone-50">
          <tr>
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={cn(
                  'px-4 py-2.5 text-xs font-semibold uppercase tracking-wide text-stone-500',
                  ALIGN[column.align ?? 'left'],
                  column.hideOnMobile && 'hidden md:table-cell',
                  column.className,
                )}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-stone-100">
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-4 py-8 text-center text-sm text-stone-500">
                {empty ?? 'Sin registros.'}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr key={rowKey(row)} className={cn('hover:bg-stone-50/70', rowClassName?.(row))}>
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={cn(
                      'px-4 text-stone-800',
                      dense ? 'py-2' : 'py-3',
                      ALIGN[column.align ?? 'left'],
                      column.hideOnMobile && 'hidden md:table-cell',
                      column.className,
                    )}
                  >
                    {column.cell(row)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}

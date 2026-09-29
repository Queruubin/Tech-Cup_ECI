/** Shared classes for text-like form controls (Input, Select, Textarea). */
export const inputBaseClasses =
  'block w-full rounded-xl border bg-white px-3.5 py-2.5 text-sm text-ink placeholder:text-stone-400 ' +
  'transition-colors focus:outline-none focus:ring-2 disabled:cursor-not-allowed disabled:bg-stone-100 disabled:text-stone-500'

export function stateClasses(invalid: boolean | undefined): string {
  // Invalid controls carry the brand red at rest (border + faint tint); valid ones only pick it up on focus.
  return invalid
    ? 'border-brand-500 bg-brand-50/40 focus:border-brand-600 focus:ring-brand-200'
    : 'border-stone-300 focus:border-brand-500 focus:ring-brand-100'
}

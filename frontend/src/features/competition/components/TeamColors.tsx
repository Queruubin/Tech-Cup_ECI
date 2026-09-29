import { cn } from '@/lib/cn'

/** Common Spanish colour names mapped to a swatch. Unknown words simply render as text. */
const COLOR_HEX: Record<string, string> = {
  rojo: '#dc2626',
  roja: '#dc2626',
  azul: '#2563eb',
  celeste: '#38bdf8',
  verde: '#16a34a',
  amarillo: '#facc15',
  amarilla: '#facc15',
  naranja: '#ea580c',
  negro: '#111827',
  negra: '#111827',
  blanco: '#f9fafb',
  blanca: '#f9fafb',
  gris: '#6b7280',
  morado: '#7c3aed',
  purpura: '#7c3aed',
  rosado: '#ec4899',
  rosa: '#ec4899',
  cafe: '#78350f',
  marron: '#78350f',
  vinotinto: '#7f1d1d',
  dorado: '#d4af37',
  plateado: '#c0c0c0',
}

/** Lowercases and strips combining diacritics, so "Café" and "cafe" match the same key. */
function normalize(word: string): string {
  return word
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
}

/** Extracts the recognised colours from a free-text description such as "Rojo y negro". */
export function colorSwatches(colors: string): string[] {
  const found: string[] = []
  for (const word of colors.split(/[^\p{L}]+/u)) {
    const hex = COLOR_HEX[normalize(word)]
    if (hex && !found.includes(hex)) found.push(hex)
  }
  return found
}

export interface TeamColorsProps {
  colors: string
  className?: string
}

/** Shows a team's colours as swatches plus the original description. */
export function TeamColors({ colors, className }: TeamColorsProps) {
  const swatches = colorSwatches(colors)
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-xs text-stone-500', className)}>
      {swatches.length > 0 && (
        <span className="flex items-center gap-0.5" aria-hidden="true">
          {swatches.map((hex) => (
            <span key={hex} className="h-3 w-3 rounded-full ring-1 ring-inset ring-stone-300" style={{ backgroundColor: hex }} />
          ))}
        </span>
      )}
      <span className="truncate">{colors}</span>
    </span>
  )
}

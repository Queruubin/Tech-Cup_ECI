/**
 * Client-side upload constraints, mirroring the backend limits (5 MB, restricted MIME types).
 * The server remains the source of truth; these checks only give early, friendly feedback.
 */

export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024

export const IMAGE_ACCEPT = 'image/png,image/jpeg,image/webp'
export const PDF_ACCEPT = 'application/pdf'
export const RECEIPT_ACCEPT = `${IMAGE_ACCEPT},${PDF_ACCEPT}`

export const IMAGE_HINT = 'PNG, JPG o WebP, máximo 5 MB'
export const PDF_HINT = 'Archivo PDF, máximo 5 MB'
export const RECEIPT_HINT = 'PNG, JPG, WebP o PDF del comprobante de pago, máximo 5 MB'

/** Fallback MIME types for browsers that report an empty `File.type`. */
const MIME_BY_EXTENSION: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  pdf: 'application/pdf',
}

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot === -1 ? '' : name.slice(dot + 1).toLowerCase()
}

function mimeOf(file: Pick<File, 'name' | 'type'>): string {
  const reported = file.type.trim().toLowerCase()
  if (reported) return reported
  return MIME_BY_EXTENSION[extensionOf(file.name)] ?? ''
}

/**
 * Returns true when the file satisfies an HTML `accept` list
 * (`image/png`, `image/*` wildcards or `.pdf` extensions). An empty list accepts everything.
 */
export function matchesAccept(file: Pick<File, 'name' | 'type'>, accept: string | undefined): boolean {
  const tokens = (accept ?? '')
    .split(',')
    .map((token) => token.trim().toLowerCase())
    .filter(Boolean)
  if (tokens.length === 0) return true
  const mime = mimeOf(file)
  const extension = extensionOf(file.name)
  return tokens.some((token) => {
    if (token.startsWith('.')) return extension === token.slice(1)
    if (token.endsWith('/*')) return mime.startsWith(token.slice(0, -1))
    return mime === token
  })
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export interface FileConstraints {
  accept?: string
  maxBytes?: number
}

/** Returns a Spanish error message when the file violates the constraints, otherwise `undefined`. */
export function validateFile(file: Pick<File, 'name' | 'type' | 'size'>, constraints: FileConstraints): string | undefined {
  if (!matchesAccept(file, constraints.accept)) return 'Tipo de archivo no permitido.'
  if (constraints.maxBytes !== undefined && file.size > constraints.maxBytes) {
    return `El archivo supera el tamaño máximo permitido (${formatSize(constraints.maxBytes)}).`
  }
  return undefined
}

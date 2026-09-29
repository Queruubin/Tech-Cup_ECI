import { describe, expect, it } from 'vitest'
import { IMAGE_ACCEPT, MAX_UPLOAD_BYTES, PDF_ACCEPT, RECEIPT_ACCEPT, matchesAccept, validateFile } from './uploads'

function file(name: string, type: string, size = 1024): Pick<File, 'name' | 'type' | 'size'> {
  return { name, type, size }
}

describe('matchesAccept', () => {
  it('matches exact MIME types, wildcards and extensions', () => {
    expect(matchesAccept(file('foto.png', 'image/png'), IMAGE_ACCEPT)).toBe(true)
    expect(matchesAccept(file('foto.gif', 'image/gif'), IMAGE_ACCEPT)).toBe(false)
    expect(matchesAccept(file('foto.gif', 'image/gif'), 'image/*')).toBe(true)
    expect(matchesAccept(file('reglamento.pdf', 'application/pdf'), '.pdf')).toBe(true)
    expect(matchesAccept(file('reglamento.PDF', 'application/pdf'), PDF_ACCEPT)).toBe(true)
    expect(matchesAccept(file('recibo.webp', 'image/webp'), RECEIPT_ACCEPT)).toBe(true)
  })

  it('falls back to the extension when the browser reports no MIME type', () => {
    expect(matchesAccept(file('foto.JPG', ''), IMAGE_ACCEPT)).toBe(true)
    expect(matchesAccept(file('archivo.exe', ''), IMAGE_ACCEPT)).toBe(false)
  })

  it('accepts everything when no accept list is given', () => {
    expect(matchesAccept(file('cualquiera.bin', ''), undefined)).toBe(true)
    expect(matchesAccept(file('cualquiera.bin', ''), '')).toBe(true)
  })
})

describe('validateFile', () => {
  it('returns a Spanish message for a disallowed type or an oversized file', () => {
    expect(validateFile(file('virus.exe', 'application/octet-stream'), { accept: IMAGE_ACCEPT })).toBe('Tipo de archivo no permitido.')
    expect(validateFile(file('foto.png', 'image/png', MAX_UPLOAD_BYTES + 1), { accept: IMAGE_ACCEPT, maxBytes: MAX_UPLOAD_BYTES })).toBe(
      'El archivo supera el tamaño máximo permitido (5.0 MB).',
    )
  })

  it('accepts a valid file within the limit', () => {
    expect(validateFile(file('foto.png', 'image/png', MAX_UPLOAD_BYTES), { accept: IMAGE_ACCEPT, maxBytes: MAX_UPLOAD_BYTES })).toBeUndefined()
  })
})

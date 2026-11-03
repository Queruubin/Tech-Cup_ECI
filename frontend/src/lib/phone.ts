/**
 * Contact phone rule, mirroring the backend (`identity/domain/PhoneNumbers.java`):
 * - a Colombian mobile: 10 digits starting with 3, optionally preceded by `+57` or `57` → stored as `+57XXXXXXXXXX`;
 * - an international number: `+`, a country code not starting with 0 and 8–15 digits in total → `+digits`.
 *   `+57` numbers must be Colombian mobiles (a landline is not a "celular").
 * Spaces, dashes, dots and parentheses are accepted and dropped. Empty or blank input means "no phone".
 */
export const PHONE_ERROR =
  'Ingrese un celular válido: 10 dígitos que empiecen por 3 o un número internacional con +código de país.'
export const PHONE_HINT = 'Para que los organizadores puedan contactarlo.'
/** Longest raw input accepted, separators included (same limit as the backend). */
export const PHONE_MAX_INPUT_LENGTH = 30

// Java's `\s` (ASCII whitespace only), so both sides accept exactly the same input.
const SEPARATORS = /[ \t\n\v\f\r.\-()]/g
const COLOMBIAN_MOBILE = /^(?:\+?57)?(3\d{9})$/
const INTERNATIONAL = /^\+[1-9]\d{7,14}$/

/**
 * The stored form of `raw`: null when blank or invalid (use {@link validatePhone} to tell them
 * apart), `+57XXXXXXXXXX` for a Colombian mobile, `+digits` for an international number.
 */
export function normalizePhone(raw: string): string | null {
  if (!raw.trim() || raw.length > PHONE_MAX_INPUT_LENGTH) return null
  const compact = raw.trim().replace(SEPARATORS, '')
  const mobile = COLOMBIAN_MOBILE.exec(compact)
  if (mobile) return `+57${mobile[1]}`
  if (compact.startsWith('+57')) return null
  return INTERNATIONAL.test(compact) ? compact : null
}

/** Error message for an invalid phone; undefined when it is blank (optional field) or valid. */
export function validatePhone(raw: string): string | undefined {
  if (!raw.trim()) return undefined
  return normalizePhone(raw) ? undefined : PHONE_ERROR
}

/** Readable form of a stored phone: `+57 300 123 4567` for Colombian mobiles, unchanged otherwise. */
export function formatPhone(phone: string | null | undefined): string {
  if (!phone) return ''
  const mobile = /^\+57(3\d{2})(\d{3})(\d{4})$/.exec(phone)
  return mobile ? `+57 ${mobile[1]} ${mobile[2]} ${mobile[3]}` : phone
}

import { Link } from 'react-router'
import { Alert } from '@/components/molecules/Alert'
import { PHONE_FIELD_PATH } from '../phoneField'

export const MISSING_PHONE_TITLE = 'Agregue su número de celular'
export const MISSING_PHONE_TEXT =
  'Como capitán, los organizadores necesitan poder contactarlo ante cualquier novedad del torneo. Agréguelo en su perfil.'

/**
 * Warning shown below the navbar on every authenticated page to a captain without a phone
 * (`needsPhoneBanner`). It cannot be dismissed: it goes away once a phone is saved and the session
 * user is refreshed.
 */
export function MissingPhoneBanner() {
  return (
    <Alert kind="warning" title={MISSING_PHONE_TITLE}>
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
        <p>{MISSING_PHONE_TEXT}</p>
        <Link
          to={PHONE_FIELD_PATH}
          className="inline-flex h-8 shrink-0 items-center justify-center self-start rounded-xl bg-brand-600 px-3 text-sm font-medium text-white shadow-sm transition-colors hover:bg-brand-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-600 sm:self-center"
        >
          Agregar mi celular
        </Link>
      </div>
    </Alert>
  )
}

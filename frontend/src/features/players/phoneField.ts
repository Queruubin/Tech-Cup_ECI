import { userHasExactRole } from '@/store/auth.store'
import type { UserResponse } from '@/types/api'

/** Where the profile page renders the phone input; the captain banner links here to scroll to and focus it. */
export const PHONE_INPUT_ID = 'celular'
export const PHONE_FIELD_HASH = `#${PHONE_INPUT_ID}`
export const PHONE_FIELD_PATH = `/profile${PHONE_FIELD_HASH}`

/**
 * True when the user literally holds CAPTAIN (ADMIN does not imply it here: the banner is about the
 * user's own team) and has no phone yet.
 */
export function needsPhoneBanner(user: UserResponse | null): boolean {
  return !!user && userHasExactRole(user, ['CAPTAIN']) && !user.phoneNumber
}

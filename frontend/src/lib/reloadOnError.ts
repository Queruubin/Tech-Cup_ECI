/** Anything that can reload its data, e.g. a `useQuery` result. */
export interface Reloadable {
  refetch: () => unknown
}

/**
 * Runs a mutation and, when it fails, reloads the given lists before rethrowing. A failure often means
 * the server already changed the row (resolved elsewhere, withdrawn, decided by someone else), so the
 * list must stop showing the stale state; the caller still reports the error (toast/alert).
 * Reload failures are ignored: the original error is the one that matters.
 */
export async function reloadOnError<T>(action: () => Promise<T>, ...lists: Reloadable[]): Promise<T> {
  try {
    return await action()
  } catch (cause) {
    for (const list of lists) {
      try {
        void Promise.resolve(list.refetch()).catch(() => undefined)
      } catch {
        // A synchronous reload failure must not mask the original error either.
      }
    }
    throw cause
  }
}

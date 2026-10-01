package edu.escuelaing.techcup.shared.exception;

/**
 * Helpers to compose the user-facing text carried by the exceptions of this package.
 *
 * <p>Those messages are written in Spanish because the frontend shows them verbatim to the end
 * users. Spanish agrees the verb and the noun with the quantity, so an English-style
 * {@code "1 match(es)"} template reads badly; these two helpers pick the right form instead.
 *
 * <p>The Spanish name of a domain constant is <b>not</b> resolved here: every enum that reaches a
 * message exposes its own {@code label()}, so the wording of a state lives with the state.
 */
public final class Messages {

    private Messages() {
    }

    /**
     * The count followed by the noun in the matching number, e.g. {@code "1 partido"} or
     * {@code "3 partidos"}.
     */
    public static String plural(long count, String singular, String plural) {
        return count + " " + agree(count, singular, plural);
    }

    /**
     * The form that agrees with the count, for the fragments that carry no number themselves
     * (typically a verb): {@code agree(1, "se envió", "se enviaron")}.
     */
    public static String agree(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    /**
     * Refusal for any change that would add players to a team whose roster is frozen because it
     * is registered (APPROVED) in an ACTIVE or IN_PROGRESS tournament. Shared by the teams module
     * (which enforces it on every membership) and the players module (which refuses early when a
     * join request or an invitation would target such a team).
     */
    public static String frozenRoster(String teamName) {
        return "El equipo '" + teamName + "' está inscrito en un torneo activo o en curso; "
                + "su plantilla no se puede modificar hasta que el torneo finalice.";
    }
}

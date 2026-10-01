package edu.escuelaing.techcup.players.domain;

/** Who started a {@link JoinRequest}: the player asking a team, or a team inviting the player. */
public enum JoinRequestDirection {
    REQUEST("solicitud de vinculación"),
    INVITATION("invitación");

    private final String label;

    JoinRequestDirection(String label) {
        this.label = label;
    }

    /**
     * Spanish name of this constant, for the user-facing messages the frontend shows verbatim.
     * Both labels are feminine nouns, so they fit sentences such as "La {label} ya está aceptada."
     */
    public String label() {
        return label;
    }
}

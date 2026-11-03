package edu.escuelaing.techcup.players.domain;

/** Predefined playing positions (spec 7.2). */
public enum Position {
    GOALKEEPER("Portero"),
    DEFENDER("Defensa"),
    MIDFIELDER("Volante"),
    FORWARD("Delantero");

    private final String label;

    Position(String label) {
        this.label = label;
    }

    /** Spanish name shown to users (same wording as the frontend labels), e.g. in the player export. */
    public String label() {
        return label;
    }
}

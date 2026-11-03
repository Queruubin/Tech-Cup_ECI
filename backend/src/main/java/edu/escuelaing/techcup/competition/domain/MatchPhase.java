package edu.escuelaing.techcup.competition.domain;

/**
 * Stages of a tournament. The group stage is a single round-robin; the three knockout phases are
 * played only when there are enough teams (see {@code KnockoutSeeding} and {@code KnockoutFixtureStrategy}).
 */
public enum MatchPhase {

    GROUP("fase de grupos"),
    QUARTERFINAL("cuartos de final"),
    SEMIFINAL("semifinal"),
    FINAL("final");

    private final String label;

    MatchPhase(String label) {
        this.label = label;
    }

    /**
     * Spanish name of this constant, for the user-facing messages the frontend shows verbatim.
     * Every enum that reaches such a message exposes the same {@code label()} accessor, so the
     * wording lives with the constant instead of in a switch somewhere in the service layer.
     */
    public String label() {
        return label;
    }

    /** A knockout phase needs a winner, so a draw must be resolved on penalties. */
    public boolean isKnockout() {
        return this != GROUP;
    }

    /** Number of matches a knockout phase is made of ({@code 0} for the group stage). */
    public int knockoutMatchCount() {
        return switch (this) {
            case QUARTERFINAL -> 4;
            case SEMIFINAL -> 2;
            case FINAL -> 1;
            case GROUP -> 0;
        };
    }

    /** The phase that follows this one in the bracket, or empty when this is the last one. */
    public MatchPhase nextKnockoutPhase() {
        return switch (this) {
            case QUARTERFINAL -> SEMIFINAL;
            case SEMIFINAL -> FINAL;
            case GROUP, FINAL -> null;
        };
    }
}

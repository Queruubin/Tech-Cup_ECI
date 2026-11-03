package edu.escuelaing.techcup.identity.domain;

/**
 * Relationship of a user with the school. Informational only: any e-mail may register, and
 * only an ADMIN may change it after registration.
 */
public enum SchoolRelation {
    STUDENT("Estudiante"),
    PROFESSOR("Profesor"),
    ADMINISTRATIVE("Personal administrativo"),
    GRADUATE("Graduado"),
    FAMILY("Familiar");

    private final String label;

    SchoolRelation(String label) {
        this.label = label;
    }

    /** Spanish name shown to users (same wording as the frontend labels), e.g. in the player export. */
    public String label() {
        return label;
    }
}

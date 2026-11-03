package edu.escuelaing.techcup.identity.domain;

/** Academic programs; the four engineering programs organize the tournament, {@code OTHER} is anything else. */
public enum AcademicProgram {
    SYSTEMS_ENGINEERING("Ingeniería de Sistemas"),
    AI_ENGINEERING("Ingeniería de Inteligencia Artificial"),
    CYBERSECURITY_ENGINEERING("Ingeniería de Ciberseguridad"),
    STATISTICS_ENGINEERING("Ingeniería Estadística"),
    OTHER("Otro");

    private final String label;

    AcademicProgram(String label) {
        this.label = label;
    }

    /** Spanish name shown to users (same wording as the frontend labels), e.g. in the player export. */
    public String label() {
        return label;
    }

    public boolean isOrganizingProgram() {
        return this != OTHER;
    }
}

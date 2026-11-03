package edu.escuelaing.techcup.identity.domain;

/** Colombian identity document types plus passport. */
public enum DocumentType {
    CC("Cédula de ciudadanía"),
    TI("Tarjeta de identidad"),
    CE("Cédula de extranjería"),
    PASSPORT("Pasaporte");

    private final String label;

    DocumentType(String label) {
        this.label = label;
    }

    /** Spanish name shown to users (same wording as the frontend labels), e.g. in the player export. */
    public String label() {
        return label;
    }
}

package edu.escuelaing.techcup.identity.api.dto;

/** Password constraints shared by the requests that set a password. */
public final class PasswordRules {

    /** At least one letter and one digit anywhere in the value; length is checked separately. */
    public static final String LETTER_AND_DIGIT = "^(?=.*\\p{L})(?=.*\\d).*$";
    public static final String LETTER_AND_DIGIT_MESSAGE = "La contraseña debe incluir al menos una letra y un número.";

    private PasswordRules() {
    }
}

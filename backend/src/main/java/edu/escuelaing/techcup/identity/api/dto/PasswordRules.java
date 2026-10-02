package edu.escuelaing.techcup.identity.api.dto;

/** Password constraints shared by the requests that set a password. */
public final class PasswordRules {

    /** At least one uppercase letter and one digit anywhere in the value; length is checked separately. */
    public static final String UPPERCASE_AND_DIGIT = "^(?=.*\\p{Lu})(?=.*\\d).*$";
    public static final String UPPERCASE_AND_DIGIT_MESSAGE =
            "La contraseña debe incluir al menos una letra mayúscula y un número.";

    private PasswordRules() {
    }
}

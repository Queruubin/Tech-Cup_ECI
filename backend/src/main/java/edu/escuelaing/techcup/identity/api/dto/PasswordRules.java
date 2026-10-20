package edu.escuelaing.techcup.identity.api.dto;

/** Password constraints shared by the requests that set a password. */
public final class PasswordRules {

    /** At least one uppercase letter and one digit anywhere in the value; length is checked separately. */
    public static final String UPPERCASE_AND_DIGIT = "^(?=.*\\p{Lu})(?=.*\\d).*$";
    public static final String UPPERCASE_AND_DIGIT_MESSAGE =
            "La contraseña debe incluir al menos una letra mayúscula y un número.";

    /**
     * BCrypt hashes at most 72 bytes (and the encoder refuses more), so besides the 72-character
     * limit the UTF-8 encoding must fit too: accented letters take two bytes, emojis four.
     */
    public static final int MAX_BYTES = 72;
    public static final String MAX_BYTES_MESSAGE = "La contraseña no puede ocupar más de 72 bytes: algunos caracteres "
            + "especiales o emojis cuentan doble.";

    private PasswordRules() {
    }
}

package edu.escuelaing.techcup.identity.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule and normalisation of the optional contact phone of a user (pure domain, no framework).
 * <ul>
 *   <li>A Colombian mobile: 10 digits starting with 3, optionally preceded by {@code +57} or
 *       {@code 57}. Stored as {@code +57XXXXXXXXXX}.</li>
 *   <li>An international number: a leading {@code +}, a country code that does not start with 0
 *       and 8 to 15 digits in total (E.164). Stored as {@code +} followed by the digits only.
 *       {@code +57} numbers must be Colombian mobiles (a landline is not a "celular").</li>
 *   <li>Spaces, dashes, dots and parentheses are accepted in the input and dropped.</li>
 *   <li>A null, empty or blank input means "no phone".</li>
 * </ul>
 * The frontend mirrors this rule ({@code frontend/src/lib/phone.ts}) with the same message.
 */
public final class PhoneNumbers {

    public static final String INVALID_MESSAGE = "Ingrese un celular válido: 10 dígitos que empiecen por 3 "
            + "o un número internacional con +código de país.";
    /** Longest raw input accepted (separators included); the stored value has at most 16 characters. */
    public static final int MAX_INPUT_LENGTH = 30;

    private static final String COLOMBIA_PREFIX = "+57";
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-()]");
    private static final Pattern COLOMBIAN_MOBILE = Pattern.compile("(?:\\+?57)?(3\\d{9})");
    private static final Pattern INTERNATIONAL = Pattern.compile("\\+[1-9]\\d{7,14}");

    private PhoneNumbers() {
    }

    /** True for a null, empty or blank input (no phone). */
    public static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    /** True when the input is blank or a phone this rule accepts. */
    public static boolean isValid(String raw) {
        return isBlank(raw) || canonical(raw) != null;
    }

    /**
     * The stored form of {@code raw}: null when it is blank, {@code +57XXXXXXXXXX} for a Colombian
     * mobile, {@code +digits} for an international number.
     *
     * @throws IllegalArgumentException with {@link #INVALID_MESSAGE} when the input is not a valid phone
     */
    public static String normalize(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String canonical = canonical(raw);
        if (canonical == null) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        return canonical;
    }

    private static String canonical(String raw) {
        if (raw.length() > MAX_INPUT_LENGTH) {
            return null;
        }
        String compact = SEPARATORS.matcher(raw.strip()).replaceAll("");
        Matcher mobile = COLOMBIAN_MOBILE.matcher(compact);
        if (mobile.matches()) {
            return COLOMBIA_PREFIX + mobile.group(1);
        }
        if (compact.startsWith(COLOMBIA_PREFIX)) {
            return null;
        }
        return INTERNATIONAL.matcher(compact).matches() ? compact : null;
    }
}

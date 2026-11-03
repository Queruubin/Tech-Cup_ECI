package edu.escuelaing.techcup.tournaments.domain;

import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Rule of the university-form link of a tournament (pure domain). The link is rendered as an
 * anchor for captains, so only an absolute {@code http} or {@code https} URL with a host is
 * accepted: {@code javascript:}, {@code data:}, {@code ftp:}, relative paths and anything that does
 * not parse as a URI (spaces included) are refused. The input is trimmed and stored as typed.
 * The frontend mirrors this rule ({@code frontend/src/features/tournaments/formUrl.ts}).
 */
public final class FormUrls {

    public static final int MAX_LENGTH = 500;
    public static final String REQUIRED_MESSAGE = "Ingrese la URL del formulario de la Escuela.";
    public static final String TOO_LONG_MESSAGE =
            "La URL del formulario no puede superar los " + MAX_LENGTH + " caracteres.";
    public static final String INVALID_MESSAGE = "Ingrese una URL válida que empiece por http:// o https://.";

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private FormUrls() {
    }

    /**
     * The value to store for {@code raw}: the trimmed URL.
     *
     * @throws InvalidRequestException (400) when it is blank, longer than {@value #MAX_LENGTH}
     *                                 characters or not an absolute http/https URL with a host
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException(REQUIRED_MESSAGE);
        }
        String url = raw.strip();
        if (url.length() > MAX_LENGTH) {
            throw new InvalidRequestException(TOO_LONG_MESSAGE);
        }
        if (!isAbsoluteHttpUrl(url)) {
            throw new InvalidRequestException(INVALID_MESSAGE);
        }
        return url;
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return uri.isAbsolute()
                && !uri.isOpaque()
                && scheme != null
                && SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                && host != null
                && !host.isBlank();
    }
}

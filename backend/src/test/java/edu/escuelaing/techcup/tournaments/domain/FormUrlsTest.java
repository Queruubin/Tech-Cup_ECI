package edu.escuelaing.techcup.tournaments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The university-form link is rendered as an anchor: only absolute http/https URLs with a host pass. */
class FormUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://forms.office.com/r/AbC123",
            "http://escuelaing.edu.co/formulario",
            "HTTPS://Example.com/path?a=1&b=2#top",
            "https://example.com:8443/form",
            "https://sub.domain.example.co/a/b/c"
    })
    void acceptsAbsoluteHttpAndHttpsUrls(String url) {
        assertThat(FormUrls.normalize(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"  https://example.com/form  ", "\thttps://example.com/form\n"})
    void trimsTheInput(String url) {
        assertThat(FormUrls.normalize(url)).isEqualTo("https://example.com/form");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JavaScript://example.com/%0Aalert(1)",
            "data:text/html,<script>alert(1)</script>",
            "ftp://example.com/form",
            "mailto:someone@example.com",
            "/forms/registration",
            "forms/registration",
            "example.com/form",
            "//example.com/form",
            "https://",
            "https:///path-only",
            "http:example.com",
            "https://exa mple.com/form",
            "https://example.com/my form",
            "https://example.com/<script>"
    })
    void refusesAnythingButAnAbsoluteHttpUrl(String url) {
        assertThatThrownBy(() -> FormUrls.normalize(url))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Ingrese una URL válida que empiece por http:// o https://.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void isRequired(String url) {
        assertThatThrownBy(() -> FormUrls.normalize(url))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage(FormUrls.REQUIRED_MESSAGE);
    }

    @Test
    void acceptsAtMostFiveHundredCharacters() {
        String prefix = "https://example.com/";
        String longest = prefix + "a".repeat(FormUrls.MAX_LENGTH - prefix.length());

        assertThat(FormUrls.normalize(longest)).hasSize(500);
        assertThatThrownBy(() -> FormUrls.normalize(longest + "a"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("La URL del formulario no puede superar los 500 caracteres.");
    }
}

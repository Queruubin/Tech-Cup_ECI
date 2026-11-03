package edu.escuelaing.techcup.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The phone rule: Colombian mobiles and "+" international numbers, normalised; blank = no phone. */
class PhoneNumbersTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            // Colombian mobiles, with every accepted separator and prefix.
            "3001234567          | +573001234567",
            "300 123 4567        | +573001234567",
            "300-123-4567        | +573001234567",
            "300.123.4567        | +573001234567",
            "(300) 123 4567      | +573001234567",
            "+57 300 123 4567    | +573001234567",
            "+573001234567       | +573001234567",
            "57 300 123 4567     | +573001234567",
            "573001234567        | +573001234567",
            "'  321 987 6543  '  | +573219876543",
            // International numbers: "+" and 8-15 digits, separators dropped.
            "+1 202 555 0143     | +12025550143",
            "+44 20 7946 0958    | +442079460958",
            "+34-612-345-678     | +34612345678",
            "+49 30 1234         | +49301234",
            "+123456789012345    | +123456789012345",
    })
    void normalisesValidPhones(String raw, String expected) {
        assertThat(PhoneNumbers.isValid(raw)).isTrue();
        assertThat(PhoneNumbers.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {
            "300123456",            // 9 digits
            "30012345678",          // 11 digits
            "2001234567",           // 10 digits not starting with 3
            "6011234567",           // Bogotá landline
            "6012345",              // 7-digit landline
            "+57 601 1234567",      // +57 landline: a Colombian number must be a mobile
            "+57 300 123 456",      // +57 mobile one digit short
            "+1234567",             // 7 digits after "+"
            "+1234567890123456",    // 16 digits after "+"
            "+0123456789",          // a country code never starts with 0
            "300 ABC 4567",         // letters
            "tres cero cero",
            "300/123/4567",         // unsupported separator
            "++573001234567",
            "57 300 123 4567 ext 2",
            "3001234567+",
            "123456789012345678901234567890123", // longer than the accepted input
    })
    void rejectsInvalidPhones(String raw) {
        assertThat(PhoneNumbers.isValid(raw)).isFalse();
        assertThatThrownBy(() -> PhoneNumbers.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PhoneNumbers.INVALID_MESSAGE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void blankMeansNoPhone(String raw) {
        assertThat(PhoneNumbers.isBlank(raw)).isTrue();
        assertThat(PhoneNumbers.isValid(raw)).isTrue();
        assertThat(PhoneNumbers.normalize(raw)).isNull();
    }

    @Test
    void theMessageExplainsBothAcceptedForms() {
        assertThat(PhoneNumbers.INVALID_MESSAGE).isEqualTo("Ingrese un celular válido: 10 dígitos que empiecen "
                + "por 3 o un número internacional con +código de país.");
    }
}

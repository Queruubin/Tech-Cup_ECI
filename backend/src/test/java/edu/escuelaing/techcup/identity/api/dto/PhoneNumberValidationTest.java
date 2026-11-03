package edu.escuelaing.techcup.identity.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import edu.escuelaing.techcup.identity.domain.PhoneNumbers;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Register and the basic-info update validate the optional phone with the same rule and message (HTTP 400). */
class PhoneNumberValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "300 123 4567", "+57 300-123-4567", "+44 20 7946 0958"})
    void acceptsBlankOrValidPhones(String phone) {
        assertThat(messages(register(phone))).isEmpty();
        assertThat(messages(update(phone))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "6011234567", "300 ABC 4567", "+1234567890123456"})
    void rejectsInvalidPhonesWithTheSpanishMessage(String phone) {
        assertThat(messages(register(phone))).containsExactly(PhoneNumbers.INVALID_MESSAGE);
        assertThat(messages(update(phone))).containsExactly(PhoneNumbers.INVALID_MESSAGE);
    }

    private static <T> Set<String> messages(T request) {
        return validator.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("phoneNumber"))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static RegisterRequest register(String phone) {
        return new RegisterRequest("Ana Diaz", "ana@example.com", "Secret123*", null,
                AcademicProgram.SYSTEMS_ENGINEERING, null, LocalDate.of(2004, 3, 4), DocumentType.CC, "1001", null,
                phone);
    }

    private static UpdateUserRequest update(String phone) {
        return new UpdateUserRequest("Ana Diaz", null, null, null, phone);
    }
}

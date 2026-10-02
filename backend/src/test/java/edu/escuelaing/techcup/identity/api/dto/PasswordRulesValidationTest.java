package edu.escuelaing.techcup.identity.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every request that sets a password enforces the same rule: 8-72 characters, an uppercase letter and a digit. */
class PasswordRulesValidationTest {

    private static final String SIZE_MESSAGE = "La contraseña debe tener entre 8 y 72 caracteres.";

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
    @ValueSource(strings = {"clave1234", "ClaveSegura"})
    void registerRejectsPasswordWithoutUppercaseOrDigit(String password) {
        assertThat(messages(register(password), "password"))
                .containsExactly(PasswordRules.UPPERCASE_AND_DIGIT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clave1234", "ClaveSegura"})
    void createRefereeRejectsPasswordWithoutUppercaseOrDigit(String password) {
        assertThat(messages(referee(password), "password"))
                .containsExactly(PasswordRules.UPPERCASE_AND_DIGIT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clave1234", "ClaveSegura"})
    void changeAndResetRejectPasswordWithoutUppercaseOrDigit(String password) {
        assertThat(messages(new ChangePasswordRequest("anything", password), "newPassword"))
                .containsExactly(PasswordRules.UPPERCASE_AND_DIGIT_MESSAGE);
        assertThat(messages(new ResetPasswordRequest(password), "newPassword"))
                .containsExactly(PasswordRules.UPPERCASE_AND_DIGIT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Clave1234", "ÁRBOL2026", "1abcdefZ"})
    void everyRequestAcceptsAValidPassword(String password) {
        assertValidEverywhere(password);
    }

    @Test
    void everyRequestAcceptsAPasswordAtTheBcryptLimit() {
        assertValidEverywhere("C1" + "a".repeat(70));
    }

    private static void assertValidEverywhere(String password) {
        assertThat(messages(register(password), "password")).isEmpty();
        assertThat(messages(referee(password), "password")).isEmpty();
        assertThat(messages(new ChangePasswordRequest("anything", password), "newPassword")).isEmpty();
        assertThat(messages(new ResetPasswordRequest(password), "newPassword")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Clave12", "A1"})
    void registerAndCreateRefereeRejectShortPasswords(String password) {
        assertThat(messages(register(password), "password")).containsExactly(SIZE_MESSAGE);
        assertThat(messages(referee(password), "password")).containsExactly(SIZE_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(ints = {73, 100})
    void registerAndCreateRefereeRejectPasswordsOverTheBcryptLimit(int length) {
        String password = "C1" + "a".repeat(length - 2);
        assertThat(messages(register(password), "password")).containsExactly(SIZE_MESSAGE);
        assertThat(messages(referee(password), "password")).containsExactly(SIZE_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clave1234", "ClaveSegura", "abc"})
    void loginKeepsAcceptingLegacyPasswords(String password) {
        assertThat(messages(new LoginRequest("ana@example.com", password), "password")).isEmpty();
    }

    private static <T> Set<String> messages(T request, String property) {
        return validator.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals(property))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static RegisterRequest register(String password) {
        return new RegisterRequest("Ana Diaz", "ana@example.com", password, null,
                AcademicProgram.SYSTEMS_ENGINEERING, 3, LocalDate.of(2004, 3, 4), DocumentType.CC, "1001", null);
    }

    private static CreateRefereeRequest referee(String password) {
        return new CreateRefereeRequest("Luis Perez", "luis@example.com", password, LocalDate.of(1990, 5, 6),
                DocumentType.CC, "2002");
    }
}

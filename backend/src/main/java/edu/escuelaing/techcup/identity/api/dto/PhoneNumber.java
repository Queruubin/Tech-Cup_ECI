package edu.escuelaing.techcup.identity.api.dto;

import edu.escuelaing.techcup.identity.domain.PhoneNumbers;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string is empty (no phone) or a phone accepted by {@link PhoneNumbers}: a Colombian
 * mobile or an international number with a leading "+". {@code null} and blank values are valid.
 */
@Documented
@Constraint(validatedBy = PhoneNumberValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PhoneNumber {

    String message() default PhoneNumbers.INVALID_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

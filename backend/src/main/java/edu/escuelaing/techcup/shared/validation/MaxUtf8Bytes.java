package edu.escuelaing.techcup.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string, encoded as UTF-8, takes at most {@link #value()} bytes. {@code null} is
 * valid (combine with {@code @NotBlank}). Needed where a downstream limit counts bytes rather
 * than characters, e.g. BCrypt, which refuses passwords longer than 72 bytes: an accented letter
 * takes two bytes and an emoji four, so a 72-character password may still be too long.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    /** The maximum number of UTF-8 bytes. */
    int value();

    String message() default "El valor ocupa demasiados bytes.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

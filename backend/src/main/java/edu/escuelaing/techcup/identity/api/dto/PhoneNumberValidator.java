package edu.escuelaing.techcup.identity.api.dto;

import edu.escuelaing.techcup.identity.domain.PhoneNumbers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link PhoneNumber} with the domain rule of {@link PhoneNumbers}. */
public class PhoneNumberValidator implements ConstraintValidator<PhoneNumber, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || PhoneNumbers.isValid(value.toString());
    }
}

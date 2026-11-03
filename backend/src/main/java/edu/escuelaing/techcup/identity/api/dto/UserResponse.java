package edu.escuelaing.techcup.identity.api.dto;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import java.time.LocalDate;
import java.util.List;

public record UserResponse(
        Long id,
        String fullName,
        String email,
        /** Contact phone ({@code +57XXXXXXXXXX} or {@code +digits}); null when none or not visible to the viewer. */
        String phoneNumber,
        SchoolRelation schoolRelation,
        AcademicProgram academicProgram,
        Integer semester,
        UserStatus status,
        LocalDate birthDate,
        DocumentType documentType,
        String documentNumber,
        List<String> roles,
        boolean hasProfile,
        Long teamId) {
}

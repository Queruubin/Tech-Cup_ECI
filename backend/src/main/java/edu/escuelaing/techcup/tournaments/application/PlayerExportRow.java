package edu.escuelaing.techcup.tournaments.application;

import java.time.LocalDate;

/**
 * One player of the tournament export, already in user-facing (Spanish) terms. Nullable fields
 * are left empty in the spreadsheet: a user may have no phone or no school relation yet, only students have
 * a semester, and a member without a sport profile has no position or jersey.
 */
public record PlayerExportRow(
        String fullName,
        String email,
        /** Normalised contact phone ({@code +57...}); written as text so Excel keeps the "+". */
        String phoneNumber,
        String documentType,
        String documentNumber,
        LocalDate birthDate,
        Integer age,
        String schoolRelation,
        String academicProgram,
        Integer semester,
        String teamName,
        boolean captain,
        String position,
        Integer jerseyNumber,
        String registrationStatus) {
}

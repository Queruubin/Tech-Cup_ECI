package edu.escuelaing.techcup.tournaments.api.dto;

import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import java.time.Instant;

/**
 * A team's registration. {@code receiptFileId} and {@code formEvidenceFileId} reference private
 * files (staff and the team captain only, see {@code FileAccessGuard}); readers who may not open
 * them get the {@link #withoutReceipt()} copy. {@code hasFormEvidence} stays visible to everybody
 * who sees the registration, so the UI can explain why an approval is blocked.
 */
public record RegistrationResponse(
        Long id,
        Long tournamentId,
        Long teamId,
        String teamName,
        String receiptFileId,
        String formEvidenceFileId,
        boolean hasFormEvidence,
        RegistrationStatus status,
        String reviewNote,
        Instant createdAt,
        Instant reviewedAt) {

    public static RegistrationResponse from(Registration registration) {
        return new RegistrationResponse(
                registration.getId(),
                registration.getTournament().getId(),
                registration.getTeam().getId(),
                registration.getTeam().getName(),
                registration.getReceiptFileId(),
                registration.getFormEvidenceFileId(),
                registration.hasFormEvidence(),
                registration.getStatus(),
                registration.getReviewNote(),
                registration.getCreatedAt(),
                registration.getReviewedAt());
    }

    /**
     * The same registration with the receipt and form-evidence references hidden, for readers who
     * may not open them.
     */
    public RegistrationResponse withoutReceipt() {
        return new RegistrationResponse(id, tournamentId, teamId, teamName, null, null, hasFormEvidence, status,
                reviewNote, createdAt, reviewedAt);
    }
}

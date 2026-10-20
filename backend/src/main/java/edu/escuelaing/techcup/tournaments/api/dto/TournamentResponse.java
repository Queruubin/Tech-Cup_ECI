package edu.escuelaing.techcup.tournaments.api.dto;

import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Public view of a tournament. {@code approvedTeams} is the number of APPROVED registrations;
 * {@code suspendedFromStatus} is the status a SUSPENDED tournament will return to (null otherwise).
 */
public record TournamentResponse(
        Long id,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate registrationDeadline,
        int maxTeams,
        BigDecimal fee,
        TournamentStatus status,
        String rulebookFileId,
        List<VenueResponse> venues,
        long approvedTeams,
        TournamentStatus suspendedFromStatus) {
}

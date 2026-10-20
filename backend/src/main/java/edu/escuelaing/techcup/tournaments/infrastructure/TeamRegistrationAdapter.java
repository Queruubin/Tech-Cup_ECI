package edu.escuelaing.techcup.tournaments.infrastructure;

import edu.escuelaing.techcup.teams.application.TeamRegistrationPort;
import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the teams module's {@link TeamRegistrationPort} over the registrations this module
 * owns, so a team can be deleted without teams depending on tournaments code.
 */
@Component
public class TeamRegistrationAdapter implements TeamRegistrationPort {

    private static final Set<RegistrationStatus> LIVE =
            Set.of(RegistrationStatus.UNDER_REVIEW, RegistrationStatus.APPROVED);
    private static final Set<RegistrationStatus> CLOSED =
            Set.of(RegistrationStatus.REJECTED, RegistrationStatus.CANCELLED);

    private final RegistrationRepository registrations;

    public TeamRegistrationAdapter(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> tournamentsWithLiveRegistrationOf(Long teamId) {
        return registrations.findTournamentNamesOfTeamByStatus(teamId, LIVE);
    }

    @Override
    @Transactional
    public List<String> deleteClosedRegistrationsOf(Long teamId) {
        List<Registration> closed = registrations.findByTeamIdAndStatusIn(teamId, CLOSED);
        List<String> receipts = closed.stream().map(Registration::getReceiptFileId).filter(Objects::nonNull).toList();
        registrations.deleteAll(closed);
        return receipts;
    }
}

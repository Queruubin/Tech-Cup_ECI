package edu.escuelaing.techcup.teams.infrastructure;

import edu.escuelaing.techcup.teams.application.TeamLockPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** Default {@link TeamLockPort} adapter backed by {@link RegistrationLockQuery}. */
@Component
public class RegistrationTeamLockAdapter implements TeamLockPort {

    private final RegistrationLockQuery query;

    public RegistrationTeamLockAdapter(RegistrationLockQuery query) {
        this.query = query;
    }

    @Override
    public boolean isLocked(Long teamId) {
        return query.hasApprovedRegistrationInStartedTournament(teamId);
    }

    @Override
    public List<String> upcomingTournamentsOf(Long teamId) {
        return query.findUpcomingTournamentNames(teamId);
    }
}

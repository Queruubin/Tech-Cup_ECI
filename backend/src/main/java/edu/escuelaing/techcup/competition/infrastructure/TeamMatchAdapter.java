package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.teams.application.TeamMatchPort;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the teams module's {@link TeamMatchPort} question ("does this team play any match?")
 * without teams depending on competition code.
 */
@Component
public class TeamMatchAdapter implements TeamMatchPort {

    private final MatchRepository matches;

    public TeamMatchAdapter(MatchRepository matches) {
        this.matches = matches;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> tournamentsWithMatchesOf(Long teamId) {
        return matches.findTournamentNamesWithMatchesOfTeam(teamId);
    }
}

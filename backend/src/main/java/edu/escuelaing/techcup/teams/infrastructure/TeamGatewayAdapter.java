package edu.escuelaing.techcup.teams.infrastructure;

import edu.escuelaing.techcup.players.application.TeamGateway;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Exposes the teams module to the players module through the latter's {@link TeamGateway} port. */
@Component
public class TeamGatewayAdapter implements TeamGateway {

    private final TeamService teamService;

    public TeamGatewayAdapter(TeamService teamService) {
        this.teamService = teamService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamRef> findActiveTeamOf(Long userId) {
        return teamService.findActiveTeamOf(userId).map(TeamGatewayAdapter::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public TeamRef getTeam(Long teamId) {
        return toRef(teamService.requireTeam(teamId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLocked(Long teamId) {
        return teamService.isLocked(teamId);
    }

    @Override
    @Transactional
    public void addMember(Long teamId, Long userId) {
        teamService.addMember(teamId, userId);
    }

    private static TeamRef toRef(Team team) {
        return new TeamRef(team.getId(), team.getName(), team.isActive(), team.getCaptain().getId(),
                team.memberCount(), TeamEligibility.MAX_MEMBERS);
    }
}

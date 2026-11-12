package edu.escuelaing.techcup.teams.infrastructure;

import edu.escuelaing.techcup.identity.application.TeamEligibilityGuardPort;
import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.teams.application.MemberProfilePort;
import edu.escuelaing.techcup.teams.application.RosterEligibility;
import edu.escuelaing.techcup.teams.application.TeamLockPort;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import edu.escuelaing.techcup.teams.domain.TeamMember;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers identity's {@link TeamEligibilityGuardPort} with the same rule and wording as a roster
 * change ({@link RosterEligibility}). It works on repositories and ports only, never on
 * {@code TeamService}, which depends on identity's services (a bean cycle otherwise).
 */
@Component
public class TeamEligibilityGuardAdapter implements TeamEligibilityGuardPort {

    private final TeamRepository teams;
    private final TeamLockPort teamLock;
    private final MemberProfilePort memberProfiles;

    public TeamEligibilityGuardAdapter(TeamRepository teams, TeamLockPort teamLock, MemberProfilePort memberProfiles) {
        this.teams = teams;
        this.teamLock = teamLock;
        this.memberProfiles = memberProfiles;
    }

    @Override
    @Transactional
    public void requireProgramChangeKeepsTeamEligible(Long userId, AcademicProgram newProgram) {
        // Moving into an organizing program can only help the majority; nothing else depends on it.
        if (newProgram != null && newProgram.isOrganizingProgram()) {
            return;
        }
        Optional<Team> found = teams.findActiveTeamByMember(userId);
        if (found.isEmpty()) {
            return;
        }
        // Team row first, registration state second: serialises with roster changes and approvals.
        Team team = teams.findByIdForUpdate(found.get().getId()).orElse(found.get());
        List<String> upcoming = teamLock.upcomingTournamentsOf(team.getId());
        if (upcoming.isEmpty()) {
            return;
        }
        List<AppUser> members = team.getMembers().stream().map(TeamMember::getUser).toList();
        Map<Long, AcademicProgram> override = new HashMap<>(); // Map.of refuses a null program
        override.put(userId, newProgram);
        TeamEligibility.Result result =
                RosterEligibility.check(members, memberProfiles.findProfiles(team.memberIds()), override);
        if (!result.eligible()) {
            throw new BusinessRuleException(
                    RosterEligibility.registeredTeamRefusal(team.getName(), upcoming, result.problems()));
        }
    }
}

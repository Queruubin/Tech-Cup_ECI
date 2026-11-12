package edu.escuelaing.techcup.teams.application;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.exception.Messages;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs {@link TeamEligibility} on a roster (the current one or the one a change would leave) and
 * words the refusal of a change that would break it while the team is registered (APPROVED) in a
 * tournament that has not started. Shared by {@link TeamService} (membership changes) and the
 * identity guard adapter (academic program changes), so both apply exactly the approval's rules.
 */
public final class RosterEligibility {

    private RosterEligibility() {
    }

    /**
     * @param members          the roster to check
     * @param profiles         their sport profiles by user id (a missing entry means no profile)
     * @param programOverrides academic programs to use instead of the stored ones, by user id
     */
    public static TeamEligibility.Result check(Collection<AppUser> members,
                                               Map<Long, MemberProfilePort.MemberProfile> profiles,
                                               Map<Long, AcademicProgram> programOverrides) {
        List<TeamEligibility.MemberFacts> facts = members.stream()
                .map(user -> {
                    var profile = profiles.get(user.getId());
                    AcademicProgram program = programOverrides.containsKey(user.getId())
                            ? programOverrides.get(user.getId())
                            : user.getAcademicProgram();
                    return new TeamEligibility.MemberFacts(user.getId(), user.getFullName(), profile != null,
                            profile == null ? null : profile.jerseyNumber(), program);
                })
                .toList();
        return TeamEligibility.check(facts);
    }

    /**
     * "El equipo 'X' está inscrito en el torneo «Y», que aún no inicia: debe seguir cumpliendo los
     * requisitos para participar y con este cambio no los cumpliría. " followed by the problems.
     */
    public static String registeredTeamRefusal(String teamName, List<String> tournaments, List<String> problems) {
        return "El equipo '" + teamName + "' está inscrito en " + tournamentList(tournaments) + ", que aún no "
                + Messages.agree(tournaments.size(), "inicia", "inician") + ": debe seguir cumpliendo los requisitos "
                + "para participar y con este cambio no los cumpliría. " + String.join(" ", problems);
    }

    /** "el torneo «A»" or "los torneos «A», «B»". */
    static String tournamentList(List<String> names) {
        String quoted = names.stream().map(name -> "«" + name + "»").collect(Collectors.joining(", "));
        return (names.size() == 1 ? "el torneo " : "los torneos ") + quoted;
    }
}

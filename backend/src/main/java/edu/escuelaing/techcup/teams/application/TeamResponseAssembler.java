package edu.escuelaing.techcup.teams.application;

import edu.escuelaing.techcup.teams.api.dto.TeamResponse;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamMember;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds {@link TeamResponse} from a {@link Team} plus profile and lock facts from the ports. */
@Component
public class TeamResponseAssembler {

    private final MemberProfilePort memberProfiles;
    private final TeamLockPort teamLock;

    public TeamResponseAssembler(MemberProfilePort memberProfiles, TeamLockPort teamLock) {
        this.memberProfiles = memberProfiles;
        this.teamLock = teamLock;
    }

    public TeamResponse toResponse(Team team) {
        Map<Long, MemberProfilePort.MemberProfile> profiles = memberProfiles.findProfiles(team.memberIds());
        List<TeamResponse.Member> members = team.getMembers().stream()
                .sorted(Comparator.comparing(TeamMember::getJoinedAt))
                .map(member -> {
                    var user = member.getUser();
                    var profile = profiles.get(user.getId());
                    return new TeamResponse.Member(
                            user.getId(),
                            user.getFullName(),
                            profile == null ? null : profile.position(),
                            profile == null ? null : profile.jerseyNumber(),
                            user.getAcademicProgram(),
                            profile == null ? null : profile.photoFileId());
                })
                .toList();
        return new TeamResponse(
                team.getId(),
                team.getName(),
                team.getColors(),
                team.getStatus(),
                new TeamResponse.Captain(team.getCaptain().getId(), team.getCaptain().getFullName()),
                members,
                members.size(),
                teamLock.isLocked(team.getId()),
                team.getPhotoFileId());
    }
}

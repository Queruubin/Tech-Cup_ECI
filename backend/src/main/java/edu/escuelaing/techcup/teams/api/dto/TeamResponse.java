package edu.escuelaing.techcup.teams.api.dto;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import java.util.List;

/**
 * A team as the API shows it. {@code locked}: the roster is frozen because the team is APPROVED
 * in a tournament that has started (IN_PROGRESS, or SUSPENDED from IN_PROGRESS); approved in one
 * that has not started yet, it is false and the roster may still change while it stays eligible.
 */
public record TeamResponse(
        Long id,
        String name,
        String colors,
        TeamStatus status,
        Captain captain,
        List<Member> members,
        int memberCount,
        boolean locked,
        String photoFileId) {

    public record Captain(Long id, String fullName) {
    }

    public record Member(
            Long userId,
            String fullName,
            Position position,
            Integer jerseyNumber,
            AcademicProgram academicProgram,
            String photoFileId) {
    }
}

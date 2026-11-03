package edu.escuelaing.techcup.teams.api.dto;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import java.util.List;

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

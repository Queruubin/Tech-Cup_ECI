package edu.escuelaing.techcup.competition.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A sanctioned team of a finished tournament as recorded (snapshot): {@code teamId} is null once
 * the team was deleted, {@code teamName} and the members are the ones of the moment of the sanction.
 * The history is public, so members carry no account id (it stays stored in the snapshot).
 */
public record SanctionedTeamResponse(Long id, Long teamId, String teamName, String note, Instant createdAt,
                                     List<Member> members) {

    public record Member(String fullName, boolean captain) {
    }
}

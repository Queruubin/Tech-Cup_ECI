package edu.escuelaing.techcup.competition.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The public history of a FINISHED tournament (see {@code TournamentRecords} for the rules).
 * {@code podiumSource} is {@code KNOCKOUT} (final and semi-finals), {@code STANDINGS} (general
 * table, no final decided) or null when nobody played; {@code podium} holds only the positions
 * that exist. {@code thirdPlaceCandidates} are the semi-final losers compared for the third place,
 * best first. {@code participants} (approved teams and teams with matches) is what staff may pick
 * a sanctioned team from.
 */
public record TournamentHistoryResponse(
        Long tournamentId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String podiumSource,
        List<PodiumEntry> podium,
        List<TeamNumbers> thirdPlaceCandidates,
        BestDefence bestDefence,
        List<TopScorer> topScorers,
        List<SanctionedTeamResponse> sanctionedTeams,
        List<TeamRef> participants) {

    /** {@code photoFileId} is the optional podium photo, served by {@code GET .../history/podium/{position}/photo}. */
    public record PodiumEntry(int position, Long teamId, String teamName, int played, int points, int goalDifference,
                              int goalsFor, int goalsAgainst, String photoFileId) {
    }

    public record TeamNumbers(Long teamId, String teamName, int played, int points, int goalDifference, int goalsFor,
                              int goalsAgainst) {
    }

    public record BestDefence(Long teamId, String teamName, int goalsConceded, int matchesPlayed) {
    }

    public record TopScorer(Long playerId, String playerName, Long teamId, String teamName, long goals) {
    }

    public record TeamRef(Long id, String name) {
    }
}

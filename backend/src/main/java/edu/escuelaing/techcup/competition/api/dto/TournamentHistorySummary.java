package edu.escuelaing.techcup.competition.api.dto;

import java.time.LocalDate;

/** One FINISHED tournament in the public list of past tournaments; {@code champion} is null when there is none. */
public record TournamentHistorySummary(Long id, String name, LocalDate startDate, LocalDate endDate,
                                       String podiumSource, Champion champion) {

    public record Champion(Long teamId, String teamName) {
    }
}

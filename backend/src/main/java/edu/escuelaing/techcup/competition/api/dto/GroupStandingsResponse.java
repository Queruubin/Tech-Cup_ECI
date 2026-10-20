package edu.escuelaing.techcup.competition.api.dto;

import java.util.List;

/** The table of one group: its teams, computed only from the PLAYED matches of that group. */
public record GroupStandingsResponse(Long groupId, String name, List<StandingRow> rows) {
}

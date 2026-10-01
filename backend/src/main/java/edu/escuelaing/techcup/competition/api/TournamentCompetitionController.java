package edu.escuelaing.techcup.competition.api;

import edu.escuelaing.techcup.competition.api.dto.BracketResponse;
import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.api.dto.PhaseUndoneResponse;
import edu.escuelaing.techcup.competition.api.dto.StandingRow;
import edu.escuelaing.techcup.competition.api.dto.TopScorerRow;
import edu.escuelaing.techcup.competition.application.MatchService;
import edu.escuelaing.techcup.competition.application.StandingsService;
import edu.escuelaing.techcup.competition.application.StatsService;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything the competition module exposes under a tournament: the fixture list, the table, the
 * bracket and the statistics. All reads are public; generating, advancing and undoing phases is
 * restricted to organizers.
 */
@RestController
@RequestMapping("/api/tournaments/{id}")
@Tag(name = "Competition")
public class TournamentCompetitionController {

    private final MatchService matchService;
    private final StandingsService standingsService;
    private final StatsService statsService;

    public TournamentCompetitionController(MatchService matchService, StandingsService standingsService,
                                           StatsService statsService) {
        this.matchService = matchService;
        this.standingsService = standingsService;
        this.statsService = statsService;
    }

    @PostMapping("/matches/generate")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Draw the group stage of an IN_PROGRESS tournament with no fixtures yet")
    public List<MatchResponse> generate(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return matchService.generate(actor, id);
    }

    @PostMapping("/matches/advance")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Generate the next phase once the current one is over")
    public List<MatchResponse> advance(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return matchService.advance(actor, id);
    }

    @PostMapping("/matches/undo-phase")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Delete every match of the latest phase when none of them is PLAYED")
    public PhaseUndoneResponse undoPhase(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return matchService.undoPhase(actor, id);
    }

    @GetMapping("/matches")
    @Operation(summary = "Fixture list, optionally filtered by phase")
    public List<MatchResponse> matches(@PathVariable Long id,
                                       @RequestParam(value = "phase", required = false) MatchPhase phase) {
        return matchService.list(id, phase);
    }

    @GetMapping("/standings")
    @Operation(summary = "Group-stage table, computed from the played matches")
    public List<StandingRow> standings(@PathVariable Long id) {
        return standingsService.standings(id);
    }

    @GetMapping("/bracket")
    @Operation(summary = "Matches grouped by phase")
    public BracketResponse bracket(@PathVariable Long id) {
        return matchService.bracket(id);
    }

    @GetMapping("/stats/top-scorers")
    public List<TopScorerRow> topScorers(@PathVariable Long id) {
        return statsService.topScorers(id);
    }

    @GetMapping("/stats/history")
    @Operation(summary = "Played matches, most recent first")
    public List<MatchResponse> history(@PathVariable Long id) {
        return matchService.history(id);
    }

    @GetMapping("/teams/{teamId}/results")
    @Operation(summary = "Every match of one team in this tournament")
    public List<MatchResponse> teamResults(@PathVariable Long id, @PathVariable Long teamId) {
        return matchService.teamResults(id, teamId);
    }
}

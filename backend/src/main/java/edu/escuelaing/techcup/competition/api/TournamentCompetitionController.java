package edu.escuelaing.techcup.competition.api;

import edu.escuelaing.techcup.competition.api.dto.BracketResponse;
import edu.escuelaing.techcup.competition.api.dto.CreateKnockoutRequest;
import edu.escuelaing.techcup.competition.api.dto.CreateMatchRequest;
import edu.escuelaing.techcup.competition.api.dto.GroupResponse;
import edu.escuelaing.techcup.competition.api.dto.GroupStandingsResponse;
import edu.escuelaing.techcup.competition.api.dto.KnockoutSuggestionResponse;
import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.api.dto.PhaseUndoneResponse;
import edu.escuelaing.techcup.competition.api.dto.StandingRow;
import edu.escuelaing.techcup.competition.api.dto.TopScorerRow;
import edu.escuelaing.techcup.competition.application.GroupStageService;
import edu.escuelaing.techcup.competition.application.KnockoutService;
import edu.escuelaing.techcup.competition.application.MatchService;
import edu.escuelaing.techcup.competition.application.StandingsService;
import edu.escuelaing.techcup.competition.application.StatsService;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything the competition module exposes under a tournament: the groups, the fixture list, the
 * tables, the bracket and the statistics. All reads are public, except that a SUSPENDED tournament
 * answers 404 to anybody but ADMIN/ORGANIZER; managing groups, creating matches, building the
 * knockout and advancing or undoing phases is restricted to organizers (ADMIN inherits).
 */
@RestController
@RequestMapping("/api/tournaments/{id}")
@Tag(name = "Competition")
public class TournamentCompetitionController {

    private final MatchService matchService;
    private final GroupStageService groupStageService;
    private final KnockoutService knockoutService;
    private final StandingsService standingsService;
    private final StatsService statsService;

    public TournamentCompetitionController(MatchService matchService, GroupStageService groupStageService,
                                           KnockoutService knockoutService, StandingsService standingsService,
                                           StatsService statsService) {
        this.matchService = matchService;
        this.groupStageService = groupStageService;
        this.knockoutService = knockoutService;
        this.standingsService = standingsService;
        this.statsService = statsService;
    }

    // --- groups --------------------------------------------------------------------------------

    @GetMapping("/groups")
    @Operation(summary = "Groups of the tournament with their teams")
    public List<GroupResponse> groups(@PathVariable Long id, @CurrentUser(required = false) AuthenticatedUser viewer) {
        return groupStageService.list(id, viewer);
    }

    @PostMapping("/groups")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the next group (\"Grupo A\", \"Grupo B\", ...) while ACTIVE or IN_PROGRESS")
    public GroupResponse createGroup(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return groupStageService.create(actor, id);
    }

    @DeleteMapping("/groups/{groupId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a group with no team and no match")
    public void deleteGroup(@CurrentUser AuthenticatedUser actor, @PathVariable Long id, @PathVariable Long groupId) {
        groupStageService.delete(actor, id, groupId);
    }

    @PutMapping("/groups/{groupId}/teams/{teamId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Put an approved team in the group (moving it out of its group while it has no match there)")
    public GroupResponse assignTeam(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                    @PathVariable Long groupId, @PathVariable Long teamId) {
        return groupStageService.assignTeam(actor, id, groupId, teamId);
    }

    @DeleteMapping("/groups/{groupId}/teams/{teamId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Take a team out of the group while it has no match there")
    public GroupResponse removeTeam(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                    @PathVariable Long groupId, @PathVariable Long teamId) {
        return groupStageService.removeTeam(actor, id, groupId, teamId);
    }

    @PostMapping("/groups/{groupId}/round-robin")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the pairings the group is still missing (all against all)")
    public List<MatchResponse> groupRoundRobin(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                               @PathVariable Long groupId) {
        return groupStageService.roundRobin(actor, id, groupId);
    }

    // --- matches and phases ----------------------------------------------------------------------

    @PostMapping("/matches")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create one group match (IN_PROGRESS, knockout not started)")
    public MatchResponse createMatch(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                     @Valid @RequestBody CreateMatchRequest request) {
        return groupStageService.createMatch(actor, id, request);
    }

    @GetMapping("/knockout/suggestion")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Suggested first knockout round for size 2, 4 or 8 (group winners against runners-up of other groups)")
    public KnockoutSuggestionResponse knockoutSuggestion(@PathVariable Long id, @RequestParam("size") int size) {
        return knockoutService.suggest(id, size);
    }

    @PostMapping("/knockout")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the first knockout round from the confirmed pairings, in bracket order")
    public List<MatchResponse> createKnockout(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                              @Valid @RequestBody CreateKnockoutRequest request) {
        return knockoutService.create(actor, id, request);
    }

    @PostMapping("/matches/advance")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Draw the next knockout round by hand once the current one is over (never from the group stage)")
    public List<MatchResponse> advance(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return matchService.advance(actor, id);
    }

    @PostMapping("/matches/undo-phase")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Delete every match of the latest phase when none of them is PLAYED")
    public PhaseUndoneResponse undoPhase(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return matchService.undoPhase(actor, id);
    }

    // --- public reads ------------------------------------------------------------------------------

    @GetMapping("/matches")
    @Operation(summary = "Fixture list, optionally filtered by phase")
    public List<MatchResponse> matches(@PathVariable Long id,
                                       @RequestParam(value = "phase", required = false) MatchPhase phase,
                                       @CurrentUser(required = false) AuthenticatedUser viewer) {
        return matchService.list(id, phase, viewer);
    }

    @GetMapping("/standings")
    @Operation(summary = "General table, computed from the played group matches")
    public List<StandingRow> standings(@PathVariable Long id, @CurrentUser(required = false) AuthenticatedUser viewer) {
        return standingsService.standings(id, viewer);
    }

    @GetMapping("/standings/groups")
    @Operation(summary = "One table per group, computed from the played matches of each group")
    public List<GroupStandingsResponse> groupStandings(@PathVariable Long id,
                                                       @CurrentUser(required = false) AuthenticatedUser viewer) {
        return standingsService.groupStandings(id, viewer);
    }

    @GetMapping("/bracket")
    @Operation(summary = "Matches grouped by phase")
    public BracketResponse bracket(@PathVariable Long id, @CurrentUser(required = false) AuthenticatedUser viewer) {
        return matchService.bracket(id, viewer);
    }

    @GetMapping("/stats/top-scorers")
    public List<TopScorerRow> topScorers(@PathVariable Long id, @CurrentUser(required = false) AuthenticatedUser viewer) {
        return statsService.topScorers(id, viewer);
    }

    @GetMapping("/stats/history")
    @Operation(summary = "Played matches, most recent first")
    public List<MatchResponse> history(@PathVariable Long id, @CurrentUser(required = false) AuthenticatedUser viewer) {
        return matchService.history(id, viewer);
    }

    @GetMapping("/teams/{teamId}/results")
    @Operation(summary = "Every match of one team in this tournament")
    public List<MatchResponse> teamResults(@PathVariable Long id, @PathVariable Long teamId,
                                           @CurrentUser(required = false) AuthenticatedUser viewer) {
        return matchService.teamResults(id, teamId, viewer);
    }
}

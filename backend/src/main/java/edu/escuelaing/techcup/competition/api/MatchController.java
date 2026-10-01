package edu.escuelaing.techcup.competition.api;

import edu.escuelaing.techcup.competition.api.dto.LineupRequest;
import edu.escuelaing.techcup.competition.api.dto.LineupResponse;
import edu.escuelaing.techcup.competition.api.dto.MatchResponse;
import edu.escuelaing.techcup.competition.api.dto.RecordResultRequest;
import edu.escuelaing.techcup.competition.api.dto.SanctionedPlayerRow;
import edu.escuelaing.techcup.competition.api.dto.UpdateMatchRequest;
import edu.escuelaing.techcup.competition.application.LineupService;
import edu.escuelaing.techcup.competition.application.MatchService;
import edu.escuelaing.techcup.competition.application.StatsService;
import edu.escuelaing.techcup.competition.domain.CancelReason;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A single match: detail, correction, cancellation, result, reopening, lineups and sanctions. */
@RestController
@RequestMapping("/api/matches")
@Tag(name = "Competition")
public class MatchController {

    private final MatchService matchService;
    private final LineupService lineupService;
    private final StatsService statsService;

    public MatchController(MatchService matchService, LineupService lineupService, StatsService statsService) {
        this.matchService = matchService;
        this.lineupService = lineupService;
        this.statsService = statsService;
    }

    @GetMapping("/{id}")
    public MatchResponse get(@PathVariable Long id) {
        return matchService.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Correct a match while the tournament is in progress: time (past allowed), venue, "
            + "referee, and the teams while teamsEditable is true")
    public MatchResponse update(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                @Valid @RequestBody UpdateMatchRequest request) {
        return matchService.update(actor, id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Cancel a match, keeping it in the history with its reason; "
            + "a knockout match needs winnerTeamId (the team that goes through)")
    public MatchResponse cancel(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                @RequestParam("reason") CancelReason reason,
                                @RequestParam(value = "winnerTeamId", required = false) Long winnerTeamId) {
        return matchService.cancel(actor, id, reason, winnerTeamId);
    }

    @PostMapping("/{id}/result")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Record the final score with its goals and cards, or correct it while resultEditable is true")
    public MatchResponse recordResult(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                      @Valid @RequestBody RecordResultRequest request) {
        return matchService.recordResult(actor, id, request);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Send a PLAYED or CANCELLED match back to SCHEDULED, clearing its outcome (reopenable)")
    public MatchResponse reopen(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return matchService.reopen(actor, id);
    }

    @PutMapping("/{id}/lineups")
    @PreAuthorize("hasRole('CAPTAIN')")
    @Operation(summary = "Submit my team's lineup before kick-off")
    public LineupResponse saveLineup(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                     @Valid @RequestBody LineupRequest request) {
        return lineupService.save(actor, id, request);
    }

    @GetMapping("/{id}/lineups/{teamId}")
    @Operation(summary = "Lineup of one team (team members, organizer, admin or the match referee)")
    public LineupResponse lineup(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                 @PathVariable Long teamId) {
        return lineupService.find(actor, id, teamId)
                .orElseThrow(() -> new NotFoundException("El equipo " + teamId + " no tiene alineación para el partido " + id + "."));
    }

    @GetMapping("/{id}/sanctioned-players")
    @PreAuthorize("hasAnyRole('REFEREE', 'ORGANIZER', 'ADMIN')")
    @Operation(summary = "Players suspended for this match by a red card or accumulated bookings")
    public List<SanctionedPlayerRow> sanctionedPlayers(@PathVariable Long id) {
        return statsService.sanctionedPlayers(id);
    }
}

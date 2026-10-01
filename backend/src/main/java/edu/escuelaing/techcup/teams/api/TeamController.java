package edu.escuelaing.techcup.teams.api;

import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import edu.escuelaing.techcup.teams.api.dto.CreateTeamRequest;
import edu.escuelaing.techcup.teams.api.dto.EligibilityResponse;
import edu.escuelaing.techcup.teams.api.dto.TeamResponse;
import edu.escuelaing.techcup.teams.api.dto.UpdateTeamRequest;
import edu.escuelaing.techcup.teams.application.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teams")
@Tag(name = "Teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PLAYER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a team; the creator becomes its captain and first member")
    public TeamResponse create(@CurrentUser AuthenticatedUser actor, @Valid @RequestBody CreateTeamRequest request) {
        return teamService.create(actor, request);
    }

    @GetMapping
    public List<TeamResponse> list() {
        return teamService.list();
    }

    @GetMapping("/mine")
    @Operation(summary = "The active team I belong to (404 if none)")
    public TeamResponse mine(@CurrentUser AuthenticatedUser actor) {
        return teamService.findMine(actor.id())
                .orElseThrow(() -> new NotFoundException("Usted no pertenece a ningún equipo activo."));
    }

    @GetMapping("/{id}")
    public TeamResponse get(@PathVariable Long id) {
        return teamService.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('CAPTAIN')")
    @Operation(summary = "Rename or recolor the team (captain only, not while locked by a tournament)")
    public TeamResponse update(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                               @Valid @RequestBody UpdateTeamRequest request) {
        return teamService.update(actor, id, request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('CAPTAIN')")
    @Operation(summary = "Remove a member (captain only, not while locked; the captain cannot be removed)")
    public TeamResponse removeMember(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                     @PathVariable Long userId) {
        return teamService.removeMember(actor, id, userId);
    }

    @PostMapping("/{id}/inactivate")
    @PreAuthorize("hasAnyRole('CAPTAIN', 'ADMIN')")
    public TeamResponse inactivate(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return teamService.inactivate(actor, id);
    }

    @GetMapping("/{id}/eligibility")
    @Operation(summary = "Whether the team may register for a tournament, with the list of problems")
    public EligibilityResponse eligibility(@PathVariable Long id) {
        return EligibilityResponse.from(teamService.eligibility(id));
    }
}

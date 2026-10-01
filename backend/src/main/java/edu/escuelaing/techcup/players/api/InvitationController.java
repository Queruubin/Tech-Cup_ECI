package edu.escuelaing.techcup.players.api;

import edu.escuelaing.techcup.players.api.dto.InvitationCreateRequest;
import edu.escuelaing.techcup.players.api.dto.JoinRequestResponse;
import edu.escuelaing.techcup.players.application.InvitationService;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Invitations from a team captain to a free player. ADMIN inherits every role. */
@RestController
@RequestMapping("/api")
@Tag(name = "Invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping("/teams/{teamId}/invitations")
    @PreAuthorize("hasRole('CAPTAIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invite a free player to my team (captain of the team or ADMIN)")
    public JoinRequestResponse invite(@CurrentUser AuthenticatedUser actor, @PathVariable Long teamId,
                                      @Valid @RequestBody InvitationCreateRequest request) {
        return invitationService.invite(actor, teamId, request);
    }

    @GetMapping("/teams/{teamId}/invitations")
    @PreAuthorize("hasRole('CAPTAIN')")
    @Operation(summary = "Invitations sent by my team, newest first, optionally filtered by status")
    public List<JoinRequestResponse> forTeam(@CurrentUser AuthenticatedUser actor, @PathVariable Long teamId,
                                             @RequestParam(required = false) JoinRequestStatus status) {
        return invitationService.listForTeam(actor, teamId, status);
    }

    @GetMapping("/players/me/invitations")
    @PreAuthorize("hasRole('PLAYER')")
    @Operation(summary = "Invitations I received (every status), newest first")
    public List<JoinRequestResponse> mine(@CurrentUser AuthenticatedUser actor) {
        return invitationService.listMine(actor);
    }

    @PostMapping("/invitations/{id}/accept")
    @PreAuthorize("hasRole('PLAYER')")
    @Operation(summary = "Accept an invitation (invited player only); joins the team")
    public JoinRequestResponse accept(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return invitationService.accept(actor, id);
    }

    @PostMapping("/invitations/{id}/reject")
    @PreAuthorize("hasRole('PLAYER')")
    @Operation(summary = "Reject an invitation (invited player only)")
    public JoinRequestResponse reject(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return invitationService.reject(actor, id);
    }

    @PostMapping("/invitations/{id}/cancel")
    @PreAuthorize("hasRole('CAPTAIN')")
    @Operation(summary = "Cancel a pending invitation (captain of the team or ADMIN)")
    public JoinRequestResponse cancel(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return invitationService.cancel(actor, id);
    }
}

package edu.escuelaing.techcup.identity.api;

import edu.escuelaing.techcup.identity.api.dto.CreateRefereeRequest;
import edu.escuelaing.techcup.identity.api.dto.UserResponse;
import edu.escuelaing.techcup.identity.application.RefereeService;
import edu.escuelaing.techcup.identity.application.RoleService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organizer duties over people: captains and referees. ADMIN inherits ORGANIZER. */
@RestController
@RequestMapping("/api/organizer")
@PreAuthorize("hasRole('ORGANIZER')")
@Tag(name = "Organizer - People")
public class OrganizerController {

    private final RoleService roleService;
    private final RefereeService refereeService;

    public OrganizerController(RoleService roleService, RefereeService refereeService) {
        this.roleService = roleService;
        this.refereeService = refereeService;
    }

    @PostMapping("/users/{id}/captain")
    @Operation(summary = "Grant CAPTAIN to a player")
    public UserResponse grantCaptain(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return roleService.grantCaptain(actor, id);
    }

    @DeleteMapping("/users/{id}/captain")
    @Operation(summary = "Revoke CAPTAIN")
    public UserResponse revokeCaptain(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return roleService.revokeCaptain(actor, id);
    }

    @PostMapping("/referees")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a referee account")
    public UserResponse createReferee(@CurrentUser AuthenticatedUser actor,
                                      @Valid @RequestBody CreateRefereeRequest request) {
        return refereeService.create(actor, request);
    }

    @GetMapping("/referees")
    @Operation(summary = "List referees (organizers do not receive identity documents)")
    public List<UserResponse> referees(@CurrentUser AuthenticatedUser actor) {
        return refereeService.list(actor);
    }
}

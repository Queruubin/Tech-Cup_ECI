package edu.escuelaing.techcup.tournaments.api;

import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import edu.escuelaing.techcup.tournaments.api.dto.RegistrationResponse;
import edu.escuelaing.techcup.tournaments.api.dto.ReviewRegistrationRequest;
import edu.escuelaing.techcup.tournaments.application.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Decisions on an existing registration: organizer review and captain withdrawal. */
@RestController
@RequestMapping("/api/registrations")
@Tag(name = "Tournaments")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Approve a registration under review (re-checks the tournament capacity)")
    public RegistrationResponse approve(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                        @Valid @RequestBody(required = false) ReviewRegistrationRequest request) {
        return registrationService.approve(actor, id, ReviewRegistrationRequest.orEmpty(request).note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Reject a registration under review")
    public RegistrationResponse reject(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                       @Valid @RequestBody(required = false) ReviewRegistrationRequest request) {
        return registrationService.reject(actor, id, ReviewRegistrationRequest.orEmpty(request).note());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an approved team from a tournament that has not started yet")
    public void remove(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        registrationService.remove(actor, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CAPTAIN', 'ADMIN')")
    @Operation(summary = "Withdraw my team's registration while it is still under review")
    public RegistrationResponse cancel(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return registrationService.cancel(actor, id);
    }
}

package edu.escuelaing.techcup.tournaments.api;

import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import edu.escuelaing.techcup.tournaments.api.dto.CreateTournamentRequest;
import edu.escuelaing.techcup.tournaments.api.dto.HardDeleteTournamentRequest;
import edu.escuelaing.techcup.tournaments.api.dto.RegistrationResponse;
import edu.escuelaing.techcup.tournaments.api.dto.TournamentResponse;
import edu.escuelaing.techcup.tournaments.api.dto.UpdateTournamentRequest;
import edu.escuelaing.techcup.tournaments.api.dto.VenueResponse;
import edu.escuelaing.techcup.tournaments.application.RegistrationService;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Tournaments, venues, rulebook and the registration endpoints nested under a tournament.
 * Read operations are public so anonymous visitors can follow the competition.
 */
@RestController
@RequestMapping("/api/tournaments")
@Tag(name = "Tournaments")
public class TournamentController {

    private final TournamentService tournamentService;
    private final RegistrationService registrationService;

    public TournamentController(TournamentService tournamentService, RegistrationService registrationService) {
        this.tournamentService = tournamentService;
        this.registrationService = registrationService;
    }

    // --- public reads --------------------------------------------------------------------

    @GetMapping
    @Operation(summary = "All tournaments, most recent first (suspended ones only for ADMIN/ORGANIZER)")
    public List<TournamentResponse> list(@CurrentUser(required = false) AuthenticatedUser viewer) {
        return tournamentService.list(viewer);
    }

    @GetMapping("/current")
    @Operation(summary = "The latest ACTIVE or IN_PROGRESS tournament (404 when there is none)")
    public TournamentResponse current() {
        return tournamentService.findCurrent()
                .orElseThrow(() -> new NotFoundException("En este momento no hay ningún torneo activo."));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One tournament (a suspended one is 404 except for ADMIN/ORGANIZER)")
    public TournamentResponse get(@PathVariable Long id, @CurrentUser(required = false) AuthenticatedUser viewer) {
        return tournamentService.get(id, viewer);
    }

    @GetMapping("/{id}/venues")
    public List<VenueResponse> venues(@PathVariable Long id,
                                      @CurrentUser(required = false) AuthenticatedUser viewer) {
        return tournamentService.venuesOf(id, viewer);
    }

    // --- organizer lifecycle ---------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tournament in DRAFT status")
    public TournamentResponse create(@CurrentUser AuthenticatedUser actor,
                                     @Valid @RequestBody CreateTournamentRequest request) {
        return tournamentService.create(actor, request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Update a DRAFT tournament")
    public TournamentResponse update(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                     @Valid @RequestBody UpdateTournamentRequest request) {
        return tournamentService.update(actor, id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a DRAFT tournament")
    public void delete(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        tournamentService.delete(actor, id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "DRAFT -> ACTIVE (requires rulebook and at least one venue)")
    public TournamentResponse activate(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return tournamentService.activate(actor, id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "ACTIVE -> IN_PROGRESS (only on the start date, with at least 2 approved teams)")
    public TournamentResponse start(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return tournamentService.start(actor, id);
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "IN_PROGRESS -> FINISHED (end date reached or final match played)")
    public TournamentResponse finish(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return tournamentService.finish(actor, id);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    @Operation(summary = "DRAFT|ACTIVE|IN_PROGRESS -> SUSPENDED (hidden and frozen until resumed)")
    public TournamentResponse suspend(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return tournamentService.suspend(actor, id);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    @Operation(summary = "SUSPENDED -> the status it was suspended from")
    public TournamentResponse resume(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return tournamentService.resume(actor, id);
    }

    @PostMapping("/{id}/hard-delete")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a tournament in any status with everything it owns (ADMIN; confirmName must match)")
    public void hardDelete(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                           @Valid @RequestBody HardDeleteTournamentRequest request) {
        tournamentService.hardDelete(actor, id, request.confirmName());
    }

    @PostMapping(value = "/{id}/rulebook", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Upload the rulebook as a PDF")
    public TournamentResponse uploadRulebook(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                             @RequestPart("file") MultipartFile file) {
        return tournamentService.uploadRulebook(actor, id, file);
    }

    @PostMapping(value = "/{id}/venues", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a venue with an optional image")
    public VenueResponse createVenue(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                     @RequestParam("name")
                                     @NotBlank(message = "El nombre de la cancha es obligatorio.")
                                     @Size(max = 100,
                                             message = "El nombre de la cancha no puede superar los 100 caracteres.")
                                     String name,
                                     @RequestParam(value = "description", required = false)
                                     @Size(max = 500,
                                             message = "La descripción no puede superar los 500 caracteres.")
                                     String description,
                                     @RequestPart(value = "file", required = false) MultipartFile file) {
        return tournamentService.createVenue(actor, id, name, description, file);
    }

    @DeleteMapping("/{id}/venues/{venueId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVenue(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                            @PathVariable Long venueId) {
        tournamentService.deleteVenue(actor, id, venueId);
    }

    // --- registrations nested under a tournament ---------------------------------------------

    @PostMapping(value = "/{id}/registrations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CAPTAIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register my team with its payment receipt (image or PDF)")
    public RegistrationResponse register(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                         @RequestPart("file") MultipartFile file) {
        return registrationService.register(actor, id, file);
    }

    @GetMapping("/{id}/registrations")
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Every registration of the tournament, for review")
    public List<RegistrationResponse> registrations(@PathVariable Long id) {
        return registrationService.listByTournament(id);
    }

    @GetMapping("/{id}/registrations/mine")
    @PreAuthorize("hasRole('CAPTAIN')")
    @Operation(summary = "My team's registration for this tournament (404 when there is none)")
    public RegistrationResponse myRegistration(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return registrationService.findMine(actor, id)
                .orElseThrow(() -> new NotFoundException("Su equipo no está inscrito en el torneo " + id + "."));
    }
}

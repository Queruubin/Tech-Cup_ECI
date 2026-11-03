package edu.escuelaing.techcup.competition.api;

import edu.escuelaing.techcup.competition.api.dto.PodiumPhotoResponse;
import edu.escuelaing.techcup.competition.api.dto.SanctionTeamRequest;
import edu.escuelaing.techcup.competition.api.dto.SanctionedTeamResponse;
import edu.escuelaing.techcup.competition.api.dto.TournamentHistoryResponse;
import edu.escuelaing.techcup.competition.api.dto.TournamentHistorySummary;
import edu.escuelaing.techcup.competition.application.TournamentHistoryService;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import edu.escuelaing.techcup.shared.storage.StoredFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * History of FINISHED tournaments. Reads are public (GET /api/tournaments/** is open in the
 * security chain), anonymous visitors included, and so is the podium photo, streamed here rather
 * than through {@code /api/files/{id}} (which requires a session); the sanctioned teams and the
 * podium photos are managed by ORGANIZER (ADMIN inherits).
 */
@RestController
@RequestMapping("/api/tournaments")
@Tag(name = "Tournament history")
public class TournamentHistoryController {

    static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final TournamentHistoryService historyService;

    public TournamentHistoryController(TournamentHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/history")
    @Operation(summary = "FINISHED tournaments, the one that ended last first, with their champion")
    public List<TournamentHistorySummary> finished() {
        return historyService.finished();
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "History of a FINISHED tournament: podium, best defence, top scorers, sanctioned teams")
    public TournamentHistoryResponse history(@PathVariable Long id,
                                             @CurrentUser(required = false) AuthenticatedUser viewer) {
        return historyService.history(id, viewer);
    }

    @PostMapping("/{id}/history/sanctions")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a team of the tournament as sanctioned (snapshot of its name and members)")
    public SanctionedTeamResponse addSanction(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                              @Valid @RequestBody SanctionTeamRequest request) {
        return historyService.addSanction(actor, id, request.teamId(), request.note());
    }

    @DeleteMapping("/{id}/history/sanctions/{sanctionId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a sanctioned team from the history")
    public void removeSanction(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                               @PathVariable Long sanctionId) {
        historyService.removeSanction(actor, id, sanctionId);
    }

    @PutMapping(value = "/{id}/history/podium/{position}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ORGANIZER')")
    @Operation(summary = "Upload or replace the photo of podium position 1, 2 or 3")
    public PodiumPhotoResponse replacePodiumPhoto(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                                  @PathVariable int position, @RequestPart("file") MultipartFile file) {
        return historyService.replacePodiumPhoto(actor, id, position, file);
    }

    @DeleteMapping("/{id}/history/podium/{position}/photo")
    @PreAuthorize("hasRole('ORGANIZER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the photo of a podium position")
    public void removePodiumPhoto(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                                  @PathVariable int position) {
        historyService.removePodiumPhoto(actor, id, position);
    }

    @GetMapping("/{id}/history/podium/{position}/photo")
    @Operation(summary = "The photo of a podium position (public image)")
    public ResponseEntity<InputStreamResource> podiumPhoto(@PathVariable Long id, @PathVariable int position,
                                                           @CurrentUser(required = false) AuthenticatedUser viewer) {
        StoredFile file = historyService.podiumPhoto(id, position, viewer);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.filename()).build()
                        .toString())
                .header(NOSNIFF_HEADER, "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(new InputStreamResource(file.content()));
    }
}

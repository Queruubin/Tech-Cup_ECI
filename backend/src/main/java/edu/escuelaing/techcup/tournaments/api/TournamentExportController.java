package edu.escuelaing.techcup.tournaments.api;

import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import edu.escuelaing.techcup.tournaments.application.PlayerExport;
import edu.escuelaing.techcup.tournaments.application.PlayerExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative downloads about a tournament. */
@RestController
@RequestMapping("/api/tournaments")
@Tag(name = "Tournaments")
public class TournamentExportController {

    private final PlayerExportService exportService;

    public TournamentExportController(PlayerExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/{id}/players/export")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Excel (.xlsx) with every player of the approved and under-review teams (ADMIN, any status)")
    public ResponseEntity<byte[]> exportPlayers(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        PlayerExport export = exportService.export(actor, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        // The name is an ASCII slug, so a plain quoted filename is enough.
                        ContentDisposition.attachment().filename(export.filename()).build().toString())
                // Personal data: never kept by the browser or a proxy.
                .cacheControl(CacheControl.noStore())
                .body(export.content());
    }
}

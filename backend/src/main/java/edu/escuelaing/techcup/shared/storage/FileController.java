package edu.escuelaing.techcup.shared.storage;

import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Streams stored binaries (photos, venue images, receipts, rulebook) to authenticated users.
 * Receipts are restricted by {@link FileAccessGuard}. Responses are marked {@code nosniff} and
 * PDFs are sent as attachments so a browser never renders an upload inline in the app's origin.
 */
@RestController
@RequestMapping("/api/files")
@Tag(name = "Files")
public class FileController {

    static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final FileStorage fileStorage;
    private final FileAccessGuard accessGuard;

    public FileController(FileStorage fileStorage, FileAccessGuard accessGuard) {
        this.fileStorage = fileStorage;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Download a stored file with its original content type")
    public ResponseEntity<InputStreamResource> download(@CurrentUser AuthenticatedUser actor, @PathVariable String id) {
        StoredFile file = fileStorage.find(id).orElseThrow(() -> NotFoundException.of("el archivo", id));
        accessGuard.requireDownload(actor, file);
        ContentDisposition disposition = (file.isPdf() ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.filename())
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(NOSNIFF_HEADER, "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new InputStreamResource(file.content()));
    }
}

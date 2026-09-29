package edu.escuelaing.techcup.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Downloads are authorised, marked nosniff, and PDFs are never rendered inline. */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    private static final AuthenticatedUser PLAYER = new AuthenticatedUser(10L, "p@escuelaing.edu.co", Set.of("PLAYER"));

    @Mock
    private FileStorage fileStorage;
    @Mock
    private FileAccessGuard accessGuard;
    @InjectMocks
    private FileController controller;

    @Test
    void pdfsAreSentAsAttachmentsWithNosniff() {
        StoredFile file = file("rules.pdf", "application/pdf");
        when(fileStorage.find("abc")).thenReturn(Optional.of(file));

        var response = controller.download(PLAYER, "abc");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(response.getHeaders().getFirst(FileController.NOSNIFF_HEADER)).isEqualTo("nosniff");
    }

    @Test
    void imagesStayInline() {
        when(fileStorage.find("abc")).thenReturn(Optional.of(file("photo.png", "image/png")));

        var response = controller.download(PLAYER, "abc");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        assertThat(response.getHeaders().getFirst(FileController.NOSNIFF_HEADER)).isEqualTo("nosniff");
    }

    @Test
    void theGuardDecidesWhoMayDownload() {
        StoredFile file = file("receipt.pdf", "application/pdf");
        when(fileStorage.find("abc")).thenReturn(Optional.of(file));
        doThrow(new ForbiddenOperationException("No tiene permiso para ver este archivo."))
                .when(accessGuard).requireDownload(PLAYER, file);

        assertThatThrownBy(() -> controller.download(PLAYER, "abc")).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void unknownFilesAre404() {
        when(fileStorage.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.download(PLAYER, "missing")).isInstanceOf(NotFoundException.class);
    }

    private static StoredFile file(String name, String contentType) {
        return new StoredFile("abc", name, contentType, 3, FileOwner.rulebook(1L), new ByteArrayInputStream(new byte[3]));
    }
}

package edu.escuelaing.techcup.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.io.ByteArrayInputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Receipts are private to organizers, admins and the uploading captain; everything else is not. */
@ExtendWith(MockitoExtension.class)
class FileAccessGuardTest {

    private static final AuthenticatedUser CAPTAIN = new AuthenticatedUser(20L, "c@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser PLAYER = new AuthenticatedUser(10L, "p@escuelaing.edu.co", Set.of("PLAYER"));
    private static final AuthenticatedUser ORGANIZER = new AuthenticatedUser(2L, "o@escuelaing.edu.co", Set.of("ORGANIZER"));
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "a@escuelaing.edu.co", Set.of("ADMIN"));

    @Mock
    private FileAccessPolicy policy;
    @InjectMocks
    private FileAccessGuard guard;

    @Test
    void aReceiptIsServedToItsTeamCaptain() {
        when(policy.isCaptainOfTeam(20L, 5L)).thenReturn(true);

        assertThat(guard.canDownload(CAPTAIN, receipt())).isTrue();
    }

    @Test
    void aReceiptIsRefusedToAnyoneElse() {
        when(policy.isCaptainOfTeam(10L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> guard.requireDownload(PLAYER, receipt()))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void organizersAndAdminsSeeEveryReceipt() {
        assertThat(guard.canDownload(ORGANIZER, receipt())).isTrue();
        assertThat(guard.canDownload(ADMIN, receipt())).isTrue();
    }

    @Test
    void otherKindsAndLegacyFilesAreServedToAnyAuthenticatedUser() {
        assertThat(guard.canDownload(PLAYER, file(FileOwner.photo(99L)))).isTrue();
        assertThat(guard.canDownload(PLAYER, file(FileOwner.rulebook(1L)))).isTrue();
        assertThat(guard.canDownload(PLAYER, file(null))).isTrue();
    }

    private static StoredFile receipt() {
        return file(FileOwner.receipt(5L));
    }

    private static StoredFile file(FileOwner owner) {
        return new StoredFile("id", "file.bin", "application/pdf", 3, owner, new ByteArrayInputStream(new byte[3]));
    }
}

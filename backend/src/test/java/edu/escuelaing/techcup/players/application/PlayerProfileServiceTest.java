package edu.escuelaing.techcup.players.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.application.TeamGateway.TeamRef;
import edu.escuelaing.techcup.players.domain.PlayerProfile;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.players.infrastructure.PlayerProfileRepository;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/** A replaced photo is deleted only after the profile change is committed. */
@ExtendWith(MockitoExtension.class)
class PlayerProfileServiceTest {

    private static final AuthenticatedUser PLAYER = new AuthenticatedUser(10L, "p@escuelaing.edu.co", Set.of("PLAYER"));
    private static final MockMultipartFile PHOTO =
            new MockMultipartFile("file", "me.png", "image/png", new byte[]{1, 2, 3});

    @Mock
    private PlayerProfileRepository profiles;
    @Mock
    private UserService userService;
    @Mock
    private TeamGateway teamGateway;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private FileDeletionScheduler fileDeletion;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private PlayerProfileService service;

    @Test
    void uploadingAPhotoStoresItWithItsOwnerAndDeletesThePreviousOneAfterCommit() {
        PlayerProfile profile = profile("old-photo");
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.photo(10L)))).thenReturn("new-photo");

        var response = service.uploadPhoto(PLAYER, PHOTO);

        assertThat(response.photoFileId()).isEqualTo("new-photo");
        assertThat(profile.getPhotoFileId()).isEqualTo("new-photo");
        verify(fileDeletion).deleteAfterCommit("old-photo");
        verify(fileStorage, never()).delete(any());
    }

    @Test
    void theFirstPhotoHasNothingToDelete() {
        when(profiles.findById(10L)).thenReturn(Optional.of(profile(null)));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.photo(10L)))).thenReturn("new-photo");

        service.uploadPhoto(PLAYER, PHOTO);

        verify(fileDeletion).deleteAfterCommit(null);
    }

    @Test
    void thePhotoIsLockedWhileInAnActiveTeam() {
        when(profiles.findById(10L)).thenReturn(Optional.of(profile("old-photo")));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(new TeamRef(5L, "Tigers", true, 20L, 7, 12)));

        assertThatThrownBy(() -> service.uploadPhoto(PLAYER, PHOTO))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Tigers");
        verify(fileStorage, never()).store(any(), any(), any());
    }

    private static PlayerProfile profile(String photoFileId) {
        return PlayerProfile.builder()
                .userId(10L)
                .user(AppUser.builder().id(10L).fullName("Pedro").build())
                .position(Position.FORWARD)
                .jerseyNumber(9)
                .photoFileId(photoFileId)
                .build();
    }
}

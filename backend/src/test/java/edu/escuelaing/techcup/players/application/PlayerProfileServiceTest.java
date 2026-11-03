package edu.escuelaing.techcup.players.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.PlayerAgePolicy;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.players.api.dto.PlayerProfileRequest;
import edu.escuelaing.techcup.players.application.TeamGateway.TeamRef;
import edu.escuelaing.techcup.players.domain.PlayerProfile;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.players.infrastructure.PlayerProfileRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Sport-profile rules: the photo and the position are always editable, the jersey only while the
 * team is not locked by a tournament (and unique in the team); a replaced or removed photo is
 * deleted only after the change is committed.
 */
@ExtendWith(MockitoExtension.class)
class PlayerProfileServiceTest {

    private static final AuthenticatedUser PLAYER = new AuthenticatedUser(10L, "p@escuelaing.edu.co", Set.of("PLAYER"));
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin@escuelaing.edu.co", Set.of("ADMIN"));
    private static final TeamRef TIGERS = new TeamRef(5L, "Tigers", true, 20L, 8, 13);
    private static final MockMultipartFile PHOTO =
            new MockMultipartFile("file", "me.png", "image/png", new byte[]{1, 2, 3});

    @Mock
    private PlayerProfileRepository profiles;
    @Mock
    private UserService userService;
    @Mock
    private PlayerAgePolicy playerAgePolicy;
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
    void creatingAProfileRequiresAnAgeInsideThePlayerRange() {
        AppUser adult = AppUser.builder().id(10L).fullName("Pedro").birthDate(LocalDate.of(1990, 1, 1)).build();
        when(profiles.findById(10L)).thenReturn(Optional.empty());
        when(userService.getUser(10L)).thenReturn(adult);
        doThrow(new BusinessRuleException("Para ser jugador la edad debe estar entre 5 y 100 años."))
                .when(playerAgePolicy).validate(LocalDate.of(1990, 1, 1));

        assertThatThrownBy(() -> service.upsert(PLAYER, new PlayerProfileRequest(Position.FORWARD, 9)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("entre 5 y 100 años");
        verify(profiles, never()).save(any());
    }

    @Test
    void aChildCreatesTheirProfile() {
        AppUser child = AppUser.builder().id(10L).fullName("Pedro").birthDate(LocalDate.of(2016, 1, 1)).build();
        when(profiles.findById(10L)).thenReturn(Optional.empty());
        when(userService.getUser(10L)).thenReturn(child);
        when(profiles.save(any())).thenAnswer(inv -> {
            PlayerProfile saved = inv.getArgument(0);
            saved.setUserId(10L);
            return saved;
        });
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());

        var response = service.upsert(PLAYER, new PlayerProfileRequest(Position.FORWARD, 9));

        assertThat(response.jerseyNumber()).isEqualTo(9);
        verify(playerAgePolicy).validate(LocalDate.of(2016, 1, 1));
    }

    @Test
    void updatingAnExistingProfileDoesNotRecheckTheAge() {
        when(profiles.findById(10L)).thenReturn(Optional.of(profile(null)));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());

        service.upsert(PLAYER, new PlayerProfileRequest(Position.DEFENDER, 4));

        verify(playerAgePolicy, never()).validate(any());
    }

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

    // --- editing while in a team --------------------------------------------------------------

    @Test
    void thePhotoIsAlwaysEditableEvenInATeamLockedByATournament() {
        PlayerProfile profile = profile("old-photo");
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(TIGERS));
        when(teamGateway.isLocked(5L)).thenReturn(true);
        when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.photo(10L)))).thenReturn("new-photo");

        var response = service.uploadPhoto(PLAYER, PHOTO);

        assertThat(response.photoFileId()).isEqualTo("new-photo");
        assertThat(response.teamLocked()).isTrue();
        verify(fileDeletion).deleteAfterCommit("old-photo");
    }

    @Test
    void thePositionChangesInATeamLockedByATournamentWhenTheJerseyStaysTheSame() {
        PlayerProfile profile = profile(null);
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(TIGERS));
        when(teamGateway.isLocked(5L)).thenReturn(true);

        var response = service.upsert(PLAYER, new PlayerProfileRequest(Position.GOALKEEPER, 9));

        assertThat(response.position()).isEqualTo(Position.GOALKEEPER);
        assertThat(profile.getPosition()).isEqualTo(Position.GOALKEEPER);
        verify(teamGateway, never()).lockMemberIds(any());
        verify(auditService).record(eq(10L), eq(AuditAction.PROFILE_UPDATED), eq("PLAYER_PROFILE"), eq(10L), any());
    }

    @Test
    void theJerseyCannotChangeWhileTheTeamIsLockedByATournament() {
        PlayerProfile profile = profile(null);
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(TIGERS));
        when(teamGateway.lockMemberIds(5L)).thenReturn(List.of(10L, 20L));
        when(teamGateway.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.upsert(PLAYER, new PlayerProfileRequest(Position.GOALKEEPER, 10)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("No puede cambiar su dorsal mientras su equipo juega un torneo.");
        assertThat(profile.getJerseyNumber()).isEqualTo(9);
        assertThat(profile.getPosition()).isEqualTo(Position.FORWARD);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void theJerseyChangesInsideAnUnlockedTeamWhenNoTeammateWearsIt() {
        PlayerProfile profile = profile(null);
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(TIGERS));
        when(teamGateway.lockMemberIds(5L)).thenReturn(List.of(10L, 20L));
        when(teamGateway.isLocked(5L)).thenReturn(false);
        when(profiles.findAllById(List.of(20L))).thenReturn(List.of(teammate(20L, 7)));

        var response = service.upsert(PLAYER, new PlayerProfileRequest(Position.FORWARD, 10));

        assertThat(response.jerseyNumber()).isEqualTo(10);
        assertThat(response.teamLocked()).isFalse();
        assertThat(profile.getJerseyNumber()).isEqualTo(10);
    }

    @Test
    void aJerseyWornByATeammateIsRefused() {
        PlayerProfile profile = profile(null);
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(TIGERS));
        when(teamGateway.lockMemberIds(5L)).thenReturn(List.of(10L, 20L));
        when(teamGateway.isLocked(5L)).thenReturn(false);
        when(profiles.findAllById(List.of(20L))).thenReturn(List.of(teammate(20L, 10)));

        assertThatThrownBy(() -> service.upsert(PLAYER, new PlayerProfileRequest(Position.FORWARD, 10)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El número de camiseta 10 ya está en uso en el equipo 'Tigers'.");
        assertThat(profile.getJerseyNumber()).isEqualTo(9);
    }

    @Test
    void outsideATeamTheJerseyChangesWithoutTakingAnyTeamLock() {
        PlayerProfile profile = profile(null);
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());

        var response = service.upsert(PLAYER, new PlayerProfileRequest(Position.FORWARD, 23));

        assertThat(response.jerseyNumber()).isEqualTo(23);
        verify(teamGateway, never()).lockMemberIds(any());
        verify(teamGateway, never()).isLocked(any());
    }

    // --- photo removal ---------------------------------------------------------------------------

    @Test
    void theOwnerRemovesTheirPhotoAndTheFileIsDeletedAfterCommit() {
        PlayerProfile profile = profile("old-photo");
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(TIGERS));
        when(teamGateway.isLocked(5L)).thenReturn(true);

        var response = service.removeMyPhoto(PLAYER);

        assertThat(response.photoFileId()).isNull();
        assertThat(profile.getPhotoFileId()).isNull();
        verify(fileDeletion).deleteAfterCommit("old-photo");
        verify(fileStorage, never()).delete(any());
    }

    @Test
    void removingAPhotoThatDoesNotExistIsAConflict() {
        when(profiles.findById(10L)).thenReturn(Optional.of(profile(null)));

        assertThatThrownBy(() -> service.removeMyPhoto(PLAYER))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El jugador no tiene foto de perfil.");
        verify(fileDeletion, never()).deleteAfterCommit(any());
    }

    @Test
    void anAdminRemovesAPlayersPhotoForModerationWithAuditAndDeletionAfterCommit() {
        PlayerProfile profile = profile("bad-photo");
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());

        var response = service.removePhotoAsAdmin(ADMIN, 10L);

        assertThat(response.photoFileId()).isNull();
        assertThat(profile.getPhotoFileId()).isNull();
        verify(fileDeletion).deleteAfterCommit("bad-photo");
        verify(fileStorage, never()).delete(any());
        verify(auditService).record(1L, AuditAction.PLAYER_PHOTO_REMOVED_BY_ADMIN, "PLAYER_PROFILE", 10L,
                Map.of("reason", "moderación", "photoFileId", "bad-photo"));
    }

    @Test
    void onlyAnAdminRemovesAnotherPlayersPhoto() {
        AuthenticatedUser organizer = new AuthenticatedUser(2L, "o@escuelaing.edu.co", Set.of("ORGANIZER"));

        assertThatThrownBy(() -> service.removePhotoAsAdmin(organizer, 10L))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(profiles, never()).findById(any());
        verify(fileDeletion, never()).deleteAfterCommit(any());
    }

    @Test
    void afterAnAdminRemovalTheOwnerUploadsANewPhoto() {
        PlayerProfile profile = profile(null);
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.photo(10L)))).thenReturn("fresh-photo");

        assertThat(service.uploadPhoto(PLAYER, PHOTO).photoFileId()).isEqualTo("fresh-photo");
    }

    // --- players directory ---------------------------------------------------------------------

    @Test
    void thePlayersListLeavesOutUsersWithoutThePlayerRoleOrInactive() {
        PlayerProfile player = listed(10L, "Ana", UserStatus.ACTIVE, Role.PLAYER);
        PlayerProfile roleRemoved = listed(11L, "Beto", UserStatus.ACTIVE, Role.GUEST);
        PlayerProfile inactive = listed(12L, "Carla", UserStatus.INACTIVE, Role.PLAYER);
        when(profiles.findAllByPosition(null)).thenReturn(List.of(player, roleRemoved, inactive));

        assertThat(service.search(null, false)).extracting("userId").containsExactly(10L);
    }

    @Test
    void theFreeAgentSearchAlsoRequiresThePlayerRole() {
        PlayerProfile player = listed(10L, "Ana", UserStatus.ACTIVE, Role.PLAYER, Role.CAPTAIN);
        PlayerProfile roleRemoved = listed(11L, "Beto", UserStatus.ACTIVE, Role.REFEREE);
        when(profiles.findFreeAgents("FORWARD")).thenReturn(List.of(player, roleRemoved));

        assertThat(service.search(Position.FORWARD, true)).extracting("userId").containsExactly(10L);
    }

    private static PlayerProfile teammate(Long id, int jerseyNumber) {
        return PlayerProfile.builder()
                .userId(id)
                .user(AppUser.builder().id(id).fullName("Mate").build())
                .position(Position.DEFENDER)
                .jerseyNumber(jerseyNumber)
                .build();
    }

    private static PlayerProfile listed(Long id, String name, UserStatus status, Role... roles) {
        AppUser user = AppUser.builder().id(id).fullName(name).status(status).roles(Set.of(roles)).build();
        return PlayerProfile.builder().userId(id).user(user).position(Position.FORWARD).jerseyNumber(9).build();
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

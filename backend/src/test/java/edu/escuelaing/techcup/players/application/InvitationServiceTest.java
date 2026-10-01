package edu.escuelaing.techcup.players.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.identity.domain.UserStatus;
import edu.escuelaing.techcup.players.api.dto.InvitationCreateRequest;
import edu.escuelaing.techcup.players.application.TeamGateway.TeamRef;
import edu.escuelaing.techcup.players.domain.JoinRequest;
import edu.escuelaing.techcup.players.domain.JoinRequestDirection;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.players.infrastructure.JoinRequestRepository;
import edu.escuelaing.techcup.players.infrastructure.PlayerProfileRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Invitations run on a real {@link JoinRequestService} (same mocks), so acceptance exercises the
 * very membership step that request acceptance uses.
 */
@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    private static final AuthenticatedUser CAPTAIN = new AuthenticatedUser(20L, "c@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser OTHER_CAPTAIN = new AuthenticatedUser(30L, "o@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin@escuelaing.edu.co", Set.of("ADMIN"));
    private static final AuthenticatedUser INVITED = new AuthenticatedUser(10L, "p@escuelaing.edu.co", Set.of("PLAYER"));
    private static final AuthenticatedUser SOMEONE_ELSE = new AuthenticatedUser(11L, "x@escuelaing.edu.co", Set.of("PLAYER"));
    private static final TeamRef TEAM = new TeamRef(5L, "Tigers", true, 20L, 7, 12);

    @Mock
    private JoinRequestRepository requests;
    @Mock
    private PlayerProfileRepository profiles;
    @Mock
    private UserService userService;
    @Mock
    private TeamGateway teamGateway;
    @Mock
    private AuditService auditService;

    private InvitationService service;

    @BeforeEach
    void setUp() {
        JoinRequestService joinRequests = new JoinRequestService(requests, profiles, userService, teamGateway, auditService);
        service = new InvitationService(requests, profiles, userService, teamGateway, joinRequests, auditService);
    }

    // --- invite -------------------------------------------------------------------------------

    @Test
    void theCaptainInvitesAFreePlayer() {
        stubInvitablePlayer();
        when(requests.existsByTeamIdAndPlayerIdAndDirectionAndStatus(5L, 10L, JoinRequestDirection.INVITATION,
                JoinRequestStatus.PENDING)).thenReturn(false);
        when(requests.saveAndFlush(any())).thenAnswer(inv -> {
            JoinRequest saved = inv.getArgument(0);
            saved.setId(99L);
            saved.setCreatedAt(Instant.now());
            return saved;
        });

        var response = service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, "  Te queremos en el equipo  "));

        ArgumentCaptor<JoinRequest> saved = ArgumentCaptor.forClass(JoinRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getDirection()).isEqualTo(JoinRequestDirection.INVITATION);
        assertThat(saved.getValue().getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(response.direction()).isEqualTo(JoinRequestDirection.INVITATION);
        assertThat(response.teamName()).isEqualTo("Tigers");
        assertThat(response.playerId()).isEqualTo(10L);
        assertThat(response.message()).isEqualTo("Te queremos en el equipo");
        verify(auditService).record(20L, AuditAction.INVITATION_SENT, "JOIN_REQUEST", 99L,
                Map.of("teamId", 5L, "playerId", 10L));
    }

    /** Double submit: the second insert loses against the V5 unique index and gets the usual message. */
    @Test
    void aConcurrentDuplicateInvitationGetsTheSameMessageAsTheCheck() {
        stubInvitablePlayer();
        when(requests.existsByTeamIdAndPlayerIdAndDirectionAndStatus(5L, 10L, JoinRequestDirection.INVITATION,
                JoinRequestStatus.PENDING)).thenReturn(false);
        when(requests.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement", new org.hibernate.exception.ConstraintViolationException(
                        "duplicate key value", new java.sql.SQLException("duplicate key"),
                        "ux_join_requests_pending_invitation")));

        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya tiene una invitación pendiente para");
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void onlyTheCaptainOfTheTeamInvites() {
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        assertThatThrownBy(() -> service.invite(OTHER_CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void anAdminMayInviteForAnyTeam() {
        stubInvitablePlayer();
        when(requests.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.invite(ADMIN, 5L, new InvitationCreateRequest(10L, " "));

        assertThat(response.message()).isNull();
        verify(auditService).record(eq(1L), eq(AuditAction.INVITATION_SENT), anyString(), any(), any());
    }

    @Test
    void aLockedTeamCannotInvite() {
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(teamGateway.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' está inscrito en un torneo activo o en curso; su plantilla no se puede modificar hasta que el torneo finalice.");
        verify(userService, never()).getUser(10L);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void theTeamMustBeActiveAndNotFull() {
        when(teamGateway.getTeam(5L)).thenReturn(new TeamRef(5L, "Tigers", false, 20L, 7, 12));
        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no está activo");

        when(teamGateway.getTeam(5L)).thenReturn(new TeamRef(5L, "Tigers", true, 20L, 12, 12));
        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está completo");
    }

    @Test
    void theInvitedUserMustBeAnActivePlayer() {
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        AppUser guest = user(10L, Role.GUEST);
        when(userService.getUser(10L)).thenReturn(guest);
        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("rol de jugador");

        AppUser inactive = user(10L, Role.PLAYER);
        inactive.setStatus(UserStatus.INACTIVE);
        when(userService.getUser(10L)).thenReturn(inactive);
        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inactivo");
    }

    @Test
    void theInvitedPlayerNeedsASportProfile() {
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER));
        when(profiles.existsById(10L)).thenReturn(false);

        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("perfil deportivo");
    }

    @Test
    void aPlayerAlreadyInATeamCannotBeInvited() {
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER));
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(new TeamRef(6L, "Lions", true, 40L, 7, 12)));

        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Lions");
    }

    @Test
    void aTeamCannotInviteTheSamePlayerTwiceWhilePending() {
        stubInvitablePlayer();
        when(requests.existsByTeamIdAndPlayerIdAndDirectionAndStatus(5L, 10L, JoinRequestDirection.INVITATION,
                JoinRequestStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("invitación pendiente");
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void aPendingRequestOfThePlayerDoesNotBlockInvitations() {
        stubInvitablePlayer();
        when(requests.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.invite(CAPTAIN, 5L, new InvitationCreateRequest(10L, null));

        verify(requests, never()).existsByPlayerIdAndDirectionAndStatus(anyLong(), any(), any());
    }

    // --- listings -----------------------------------------------------------------------------

    @Test
    void theTeamListsItsInvitationsOnly() {
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(requests.findByTeamIdAndDirectionOrderByCreatedAtDesc(5L, JoinRequestDirection.INVITATION))
                .thenReturn(List.of(invitation(99L)));

        assertThat(service.listForTeam(CAPTAIN, 5L, null))
                .singleElement()
                .satisfies(r -> assertThat(r.direction()).isEqualTo(JoinRequestDirection.INVITATION));
        assertThatThrownBy(() -> service.listForTeam(OTHER_CAPTAIN, 5L, null))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void thePlayerListsTheInvitationsTheyReceived() {
        when(requests.findByPlayerIdAndDirectionOrderByCreatedAtDesc(10L, JoinRequestDirection.INVITATION))
                .thenReturn(List.of(invitation(99L), invitation(98L)));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        assertThat(service.listMine(INVITED)).extracting(r -> r.id()).containsExactly(99L, 98L);
    }

    // --- answer -------------------------------------------------------------------------------

    @Test
    void acceptingJoinsTheTeamWithTheRequestChecksAndCancelsEveryOtherPendingRow() {
        JoinRequest accepted = invitation(99L);
        JoinRequest otherInvitation = invitation(100L);
        JoinRequest ownRequest = invitation(101L);
        ownRequest.setDirection(JoinRequestDirection.REQUEST);
        when(requests.findById(99L)).thenReturn(Optional.of(accepted));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUserForUpdate(10L)).thenReturn(user(10L, Role.PLAYER));
        when(requests.findByPlayerIdAndStatus(10L, JoinRequestStatus.PENDING))
                .thenReturn(List.of(otherInvitation, ownRequest));

        var response = service.accept(INVITED, 99L);

        assertThat(response.status()).isEqualTo(JoinRequestStatus.ACCEPTED);
        assertThat(otherInvitation.getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        assertThat(ownRequest.getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        verify(userService).getUserForUpdate(10L);
        verify(teamGateway).addMember(5L, 10L);
        verify(auditService).record(10L, AuditAction.INVITATION_ACCEPTED, "JOIN_REQUEST", 99L,
                Map.of("teamId", 5L, "playerId", 10L));
    }

    @Test
    void aMembershipRuleViolationAbortsTheAcceptance() {
        when(requests.findById(99L)).thenReturn(Optional.of(invitation(99L)));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUserForUpdate(10L)).thenReturn(user(10L, Role.PLAYER));
        doThrow(new BusinessRuleException("El número de camiseta 9 ya está en uso en el equipo 'Tigers'."))
                .when(teamGateway).addMember(5L, 10L);

        assertThatThrownBy(() -> service.accept(INVITED, 99L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("camiseta");
        verify(auditService, never()).record(any(), eq(AuditAction.INVITATION_ACCEPTED), anyString(), any(), any());
    }

    @Test
    void onlyTheInvitedPlayerAnswers() {
        JoinRequest invitation = invitation(99L);
        when(requests.findById(99L)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.accept(SOMEONE_ELSE, 99L)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.reject(CAPTAIN, 99L)).isInstanceOf(ForbiddenOperationException.class);
        assertThat(invitation.getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        verify(teamGateway, never()).addMember(anyLong(), anyLong());
    }

    @Test
    void theInvitedPlayerRejects() {
        JoinRequest invitation = invitation(99L);
        when(requests.findById(99L)).thenReturn(Optional.of(invitation));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        var response = service.reject(INVITED, 99L);

        assertThat(response.status()).isEqualTo(JoinRequestStatus.REJECTED);
        verify(auditService).record(10L, AuditAction.INVITATION_REJECTED, "JOIN_REQUEST", 99L,
                Map.of("teamId", 5L, "playerId", 10L));
    }

    @Test
    void theCaptainCancelsAPendingInvitation() {
        JoinRequest invitation = invitation(99L);
        when(requests.findById(99L)).thenReturn(Optional.of(invitation));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        var response = service.cancel(CAPTAIN, 99L);

        assertThat(response.status()).isEqualTo(JoinRequestStatus.CANCELLED);
        verify(auditService).record(20L, AuditAction.INVITATION_CANCELLED, "JOIN_REQUEST", 99L,
                Map.of("teamId", 5L, "playerId", 10L));
    }

    @Test
    void theInvitedPlayerCannotCancel() {
        when(requests.findById(99L)).thenReturn(Optional.of(invitation(99L)));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        assertThatThrownBy(() -> service.cancel(INVITED, 99L)).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void aResolvedInvitationCannotBeAnsweredAgain() {
        JoinRequest invitation = invitation(99L);
        invitation.reject();
        when(requests.findById(99L)).thenReturn(Optional.of(invitation));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        assertThatThrownBy(() -> service.accept(INVITED, 99L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("La invitación ya está rechazada.");
    }

    @Test
    void plainRequestsAreRefusedByTheInvitationOperations() {
        JoinRequest request = invitation(99L);
        request.setDirection(JoinRequestDirection.REQUEST);
        when(requests.findById(99L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.accept(INVITED, 99L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no una invitación");
        assertThatThrownBy(() -> service.cancel(CAPTAIN, 99L)).isInstanceOf(BusinessRuleException.class);
    }

    // --- helpers ------------------------------------------------------------------------------

    private void stubInvitablePlayer() {
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUser(10L)).thenReturn(user(10L, Role.PLAYER));
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
    }

    private static AppUser user(Long id, Role... roles) {
        return AppUser.builder().id(id).fullName("Pedro").status(UserStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(roles))).build();
    }

    private static JoinRequest invitation(Long id) {
        return JoinRequest.builder()
                .id(id)
                .teamId(5L)
                .player(user(10L, Role.PLAYER))
                .status(JoinRequestStatus.PENDING)
                .direction(JoinRequestDirection.INVITATION)
                .createdAt(Instant.now())
                .build();
    }
}

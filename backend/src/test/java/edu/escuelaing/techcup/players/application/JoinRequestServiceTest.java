package edu.escuelaing.techcup.players.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.api.dto.JoinRequestCreateRequest;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JoinRequestServiceTest {

    private static final AuthenticatedUser PLAYER = new AuthenticatedUser(10L, "p@escuelaing.edu.co", Set.of("PLAYER"));
    private static final AuthenticatedUser CAPTAIN = new AuthenticatedUser(20L, "c@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser OTHER_CAPTAIN = new AuthenticatedUser(30L, "o@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final TeamRef TEAM = new TeamRef(5L, "Tigers", true, 20L, 3, 12);

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
    @InjectMocks
    private JoinRequestService service;

    @Test
    void requiresASportProfile() {
        when(profiles.existsById(10L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("perfil deportivo");
    }

    @Test
    void playerAlreadyInATeamCannotRequest() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.of(TEAM));

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya pertenece a un equipo");
    }

    @Test
    void onlyOnePendingRequestAtATime() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndDirectionAndStatus(10L, JoinRequestDirection.REQUEST, JoinRequestStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("solicitud de vinculación pendiente");
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void teamMustHaveCapacity() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndDirectionAndStatus(10L, JoinRequestDirection.REQUEST, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(new TeamRef(5L, "Tigers", true, 20L, 12, 12));

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está completo");
    }

    @Test
    void aLockedTeamIsRefusedBeforeTheRequestIsCreated() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndDirectionAndStatus(10L, JoinRequestDirection.REQUEST, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(teamGateway.isLocked(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' está inscrito en un torneo activo o en curso; su plantilla no se puede modificar hasta que el torneo finalice.");
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void teamMustBeActive() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndDirectionAndStatus(10L, JoinRequestDirection.REQUEST, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(new TeamRef(5L, "Tigers", false, 20L, 3, 12));

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no está activo");
    }

    @Test
    void createsAPendingRequest() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(profiles.findById(10L)).thenReturn(Optional.empty());
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndDirectionAndStatus(10L, JoinRequestDirection.REQUEST, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUser(10L)).thenReturn(AppUser.builder().id(10L).fullName("Pedro").build());
        when(requests.saveAndFlush(any())).thenAnswer(inv -> {
            JoinRequest r = inv.getArgument(0);
            r.setId(99L);
            r.setCreatedAt(Instant.now());
            return r;
        });

        var response = service.create(PLAYER, 5L, new JoinRequestCreateRequest("  let me in  "));

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(response.direction()).isEqualTo(JoinRequestDirection.REQUEST);
        assertThat(response.teamName()).isEqualTo("Tigers");
        assertThat(response.message()).isEqualTo("let me in");
    }

    /** Double submit: the second insert loses against the V5 unique index and gets the usual message. */
    @Test
    void aConcurrentDuplicateRequestGetsTheSameMessageAsTheCheck() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndDirectionAndStatus(10L, JoinRequestDirection.REQUEST, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUser(10L)).thenReturn(AppUser.builder().id(10L).fullName("Pedro").build());
        when(requests.saveAndFlush(any())).thenThrow(uniqueViolation("ux_join_requests_pending_request"));

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Usted ya tiene una solicitud de vinculación pendiente.");
        verifyNoInteractions(auditService);
    }

    @Test
    void anotherIntegrityViolationIsNotDisguised() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndDirectionAndStatus(10L, JoinRequestDirection.REQUEST, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUser(10L)).thenReturn(AppUser.builder().id(10L).fullName("Pedro").build());
        when(requests.saveAndFlush(any())).thenThrow(uniqueViolation("join_requests_team_id_fkey"));

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void onlyTheTeamCaptainCanAccept() {
        when(requests.findById(99L)).thenReturn(Optional.of(pending(99L)));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        assertThatThrownBy(() -> service.accept(OTHER_CAPTAIN, 99L))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(teamGateway, never()).addMember(anyLong(), anyLong());
    }

    @Test
    void acceptingAddsTheMemberAndCancelsOtherPendingRequests() {
        JoinRequest accepted = pending(99L);
        JoinRequest other = pending(100L);
        when(requests.findById(99L)).thenReturn(Optional.of(accepted));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(requests.findByPlayerIdAndStatus(10L, JoinRequestStatus.PENDING)).thenReturn(List.of(other));
        when(profiles.findById(10L)).thenReturn(Optional.empty());
        when(userService.getUserForUpdate(10L)).thenReturn(AppUser.builder().id(10L).fullName("Pedro").build());

        var response = service.accept(CAPTAIN, 99L);

        assertThat(response.status()).isEqualTo(JoinRequestStatus.ACCEPTED);
        assertThat(other.getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        verify(userService).getUserForUpdate(10L);
        verify(teamGateway).addMember(5L, 10L);
    }

    @Test
    void resolvedRequestsCannotBeAcceptedAgain() {
        JoinRequest rejected = pending(99L);
        rejected.reject();
        when(requests.findById(99L)).thenReturn(Optional.of(rejected));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);

        assertThatThrownBy(() -> service.accept(CAPTAIN, 99L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está rechazada");
    }

    @Test
    void acceptingARequestAlsoCancelsThePlayersPendingInvitations() {
        JoinRequest accepted = pending(99L);
        JoinRequest invitation = pending(101L);
        invitation.setDirection(JoinRequestDirection.INVITATION);
        when(requests.findById(99L)).thenReturn(Optional.of(accepted));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(requests.findByPlayerIdAndStatus(10L, JoinRequestStatus.PENDING)).thenReturn(List.of(invitation));
        when(profiles.findById(10L)).thenReturn(Optional.empty());
        when(userService.getUserForUpdate(10L)).thenReturn(AppUser.builder().id(10L).fullName("Pedro").build());

        service.accept(CAPTAIN, 99L);

        assertThat(invitation.getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
    }

    @Test
    void invitationsAreRefusedByTheRequestOperations() {
        JoinRequest invitation = pending(99L);
        invitation.setDirection(JoinRequestDirection.INVITATION);
        when(requests.findById(99L)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.accept(CAPTAIN, 99L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("invitación");
        assertThatThrownBy(() -> service.reject(CAPTAIN, 99L)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.cancel(PLAYER, 99L)).isInstanceOf(BusinessRuleException.class);
        assertThat(invitation.getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        verify(teamGateway, never()).addMember(anyLong(), anyLong());
    }

    @Test
    void listingsOnlyReturnRequests() {
        when(requests.findByPlayerIdAndDirectionOrderByCreatedAtDesc(10L, JoinRequestDirection.REQUEST))
                .thenReturn(List.of(pending(99L)));
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(requests.findByTeamIdAndDirectionAndStatusOrderByCreatedAtDesc(5L, JoinRequestDirection.REQUEST,
                JoinRequestStatus.PENDING)).thenReturn(List.of(pending(99L), pending(100L)));

        assertThat(service.listMine(PLAYER)).extracting(r -> r.direction()).containsExactly(JoinRequestDirection.REQUEST);
        assertThat(service.listForTeam(CAPTAIN, 5L, JoinRequestStatus.PENDING)).hasSize(2);
    }

    private static JoinRequest pending(Long id) {
        return JoinRequest.builder()
                .id(id)
                .teamId(5L)
                .player(AppUser.builder().id(10L).fullName("Pedro").build())
                .status(JoinRequestStatus.PENDING)
                .createdAt(Instant.now())
                .build();
    }

    private static org.springframework.dao.DataIntegrityViolationException uniqueViolation(String constraint) {
        return new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("duplicate key value",
                        new java.sql.SQLException("duplicate key"), constraint));
    }
}

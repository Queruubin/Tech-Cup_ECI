package edu.escuelaing.techcup.players.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.api.dto.JoinRequestCreateRequest;
import edu.escuelaing.techcup.players.application.TeamGateway.TeamRef;
import edu.escuelaing.techcup.players.domain.JoinRequest;
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
        when(requests.existsByPlayerIdAndStatus(10L, JoinRequestStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("solicitud de vinculación pendiente");
        verify(requests, never()).save(any());
    }

    @Test
    void teamMustHaveCapacity() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndStatus(10L, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(new TeamRef(5L, "Tigers", true, 20L, 12, 12));

        assertThatThrownBy(() -> service.create(PLAYER, 5L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está completo");
    }

    @Test
    void teamMustBeActive() {
        when(profiles.existsById(10L)).thenReturn(true);
        when(teamGateway.findActiveTeamOf(10L)).thenReturn(Optional.empty());
        when(requests.existsByPlayerIdAndStatus(10L, JoinRequestStatus.PENDING)).thenReturn(false);
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
        when(requests.existsByPlayerIdAndStatus(10L, JoinRequestStatus.PENDING)).thenReturn(false);
        when(teamGateway.getTeam(5L)).thenReturn(TEAM);
        when(userService.getUser(10L)).thenReturn(AppUser.builder().id(10L).fullName("Pedro").build());
        when(requests.save(any())).thenAnswer(inv -> {
            JoinRequest r = inv.getArgument(0);
            r.setId(99L);
            r.setCreatedAt(Instant.now());
            return r;
        });

        var response = service.create(PLAYER, 5L, new JoinRequestCreateRequest("  let me in  "));

        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(response.teamName()).isEqualTo("Tigers");
        assertThat(response.message()).isEqualTo("let me in");
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

    private static JoinRequest pending(Long id) {
        return JoinRequest.builder()
                .id(id)
                .teamId(5L)
                .player(AppUser.builder().id(10L).fullName("Pedro").build())
                .status(JoinRequestStatus.PENDING)
                .createdAt(Instant.now())
                .build();
    }
}

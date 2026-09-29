package edu.escuelaing.techcup.players.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.domain.JoinRequest;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamJoinRequestAdapterTest {

    @Mock
    private JoinRequestRepository requests;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private TeamJoinRequestAdapter adapter;

    @Test
    void inactivatingATeamCancelsEveryPendingRequestAddressedToIt() {
        JoinRequest first = pending(99L);
        JoinRequest second = pending(100L);
        when(requests.findByTeamIdAndStatusOrderByCreatedAtDesc(5L, JoinRequestStatus.PENDING))
                .thenReturn(List.of(first, second));

        int cancelled = adapter.cancelPendingRequestsOf(20L, 5L);

        assertThat(cancelled).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        assertThat(second.getStatus()).isEqualTo(JoinRequestStatus.CANCELLED);
        verify(auditService).record(eq(20L), eq(AuditAction.JOIN_REQUEST_CANCELLED), anyString(), eq(99L), any());
        verify(auditService).record(eq(20L), eq(AuditAction.JOIN_REQUEST_CANCELLED), anyString(), eq(100L), any());
    }

    @Test
    void aTeamWithoutPendingRequestsCancelsNothing() {
        when(requests.findByTeamIdAndStatusOrderByCreatedAtDesc(5L, JoinRequestStatus.PENDING))
                .thenReturn(List.of());

        assertThat(adapter.cancelPendingRequestsOf(20L, 5L)).isZero();
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

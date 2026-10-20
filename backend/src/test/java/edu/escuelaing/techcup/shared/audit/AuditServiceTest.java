package edu.escuelaing.techcup.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/** The administrator's audit query, in particular pages made only of anonymous events. */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditRepository repository;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(repository);
    }

    /** Regression: USER_REGISTERED / LOGIN_FAILED pages have no actor at all and used to answer 500. */
    @Test
    void aPageWhoseRowsAllHaveNoActorIsReturnedWithoutNames() {
        when(repository.findByActionOrderByCreatedAtDesc(eq(AuditAction.LOGIN_FAILED), any())).thenReturn(List.of(
                log(1L, null, AuditAction.LOGIN_FAILED), log(2L, null, AuditAction.LOGIN_FAILED)));

        List<AuditLogResponse> page = service.query(AuditAction.LOGIN_FAILED, null);

        assertThat(page).extracting(AuditLogResponse::id).containsExactly(1L, 2L);
        assertThat(page).allSatisfy(row -> {
            assertThat(row.actorUserId()).isNull();
            assertThat(row.actorName()).isNull();
        });
        verify(repository, never()).findActorNames(any());
    }

    @Test
    void actorNamesAreResolvedAndAnonymousRowsStayUnnamed() {
        when(repository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(
                log(1L, 7L, AuditAction.LOGIN), log(2L, null, AuditAction.USER_REGISTERED)));
        when(repository.findActorNames(Set.of(7L))).thenReturn(List.of(actor(7L, "Ana Organizadora")));

        List<AuditLogResponse> page = service.query(null, 10);

        assertThat(page).extracting(AuditLogResponse::actorName).containsExactly("Ana Organizadora", null);
    }

    @Test
    void aLimitOutsideOneToFiveHundredIsRefused() {
        for (int limit : new int[]{-1, 0, 501}) {
            assertThatThrownBy(() -> service.query(null, limit))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessage("El límite debe estar entre 1 y 500.");
        }
        verifyNoInteractions(repository);
    }

    @Test
    void theBoundsOfTheLimitAreAccepted() {
        when(repository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of());

        service.query(null, 1);
        service.query(null, 500);

        verify(repository).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1));
        verify(repository).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 500));
    }

    private static AuditLog log(Long id, Long actorId, AuditAction action) {
        return AuditLog.builder().id(id).actorUserId(actorId).action(action).entityType("USER").build();
    }

    private static AuditRepository.ActorName actor(Long id, String name) {
        return new AuditRepository.ActorName() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getFullName() {
                return name;
            }
        };
    }
}

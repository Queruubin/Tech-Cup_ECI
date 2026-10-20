package edu.escuelaing.techcup.tournaments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.api.dto.UpdateTournamentRequest;
import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.domain.Venue;
import edu.escuelaing.techcup.tournaments.infrastructure.RegistrationRepository;
import edu.escuelaing.techcup.tournaments.infrastructure.TournamentRepository;
import edu.escuelaing.techcup.tournaments.infrastructure.VenueRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The guards {@link TournamentService} adds on top of the {@code TournamentStatus} State machine:
 * the start window, the minimum number of approved teams, the finish rule, the edit rules per
 * status and the clean-up of files and pending registrations.
 */
@ExtendWith(MockitoExtension.class)
class TournamentServiceTest {

    private static final AuthenticatedUser ORGANIZER =
            new AuthenticatedUser(7L, "organizer@escuelaing.edu.co", Set.of("ORGANIZER"));
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final ZoneId ZONE = ZoneOffset.UTC;

    @Mock
    private TournamentRepository tournaments;
    @Mock
    private VenueRepository venues;
    @Mock
    private RegistrationRepository registrations;
    @Mock
    private UserService userService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private FileDeletionScheduler fileDeletion;
    @Mock
    private FinalMatchPort finalMatch;
    @Mock
    private AuditService auditService;

    private TournamentService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        service = new TournamentService(tournaments, venues, registrations, userService, fileStorage, fileDeletion,
                finalMatch, auditService, clock);
        lenient().when(registrations.countByTournamentIdAndStatus(eq(1L), eq(RegistrationStatus.APPROVED)))
                .thenReturn(0L);
    }

    // --- activate -----------------------------------------------------------------------------

    @Test
    void activateNeedsTheRulebook() {
        given(draft(null));

        assertThatThrownBy(() -> service.activate(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("reglamento");
    }

    @Test
    void activateNeedsAtLeastOneVenue() {
        given(draft("rulebook-file"));
        when(venues.findByTournamentIdOrderByIdAsc(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.activate(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cancha");
    }

    @Test
    void activateMovesDraftToActive() {
        Tournament tournament = given(draft("rulebook-file"));
        when(venues.findByTournamentIdOrderByIdAsc(1L)).thenReturn(List.of(new Venue()));

        service.activate(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.ACTIVE);
    }

    @Test
    void anAlreadyActiveTournamentCannotBeActivatedAgain() {
        given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        when(venues.findByTournamentIdOrderByIdAsc(1L)).thenReturn(List.of(new Venue()));

        assertThatThrownBy(() -> service.activate(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«activo»");
    }

    // --- start --------------------------------------------------------------------------------

    @Test
    void startIsRefusedBeforeTheStartDate() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.setStartDate(TODAY.plusDays(1));
        givenLocked(tournament);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha de inicio");
    }

    @Test
    void startIsAllowedAfterTheStartDateWhileTheEndDateHasNotPassed() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.setStartDate(TODAY.minusDays(3));
        givenLocked(tournament);
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(2L);

        service.start(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.IN_PROGRESS);
    }

    @Test
    void startIsRefusedAfterTheEndDate() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.setStartDate(TODAY.minusDays(40));
        tournament.setEndDate(TODAY.minusDays(1));
        givenLocked(tournament);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha de cierre");
    }

    @Test
    void startNeedsTwoApprovedRegistrations() {
        givenLocked(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(1L);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inscripciones aprobadas");
    }

    @Test
    void startMovesActiveToInProgressOnTheStartDate() {
        Tournament tournament = givenLocked(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(2L);

        service.start(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.IN_PROGRESS);
    }

    @Test
    void startCancelsTheRegistrationsStillUnderReview() {
        Tournament tournament = givenLocked(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        Registration pending = Registration.builder().id(9L).tournament(tournament)
                .team(Team.builder().id(5L).name("Tigers").build())
                .receiptFileId("receipt").status(RegistrationStatus.UNDER_REVIEW).build();
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(2L);
        when(registrations.findByTournamentIdAndStatusOrderByIdAsc(1L, RegistrationStatus.UNDER_REVIEW))
                .thenReturn(List.of(pending));
        when(userService.getUser(7L)).thenReturn(AppUser.builder().id(7L).build());

        service.start(ORGANIZER, 1L);

        assertThat(pending.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(pending.getReviewNote()).isEqualTo(TournamentService.AUTO_CANCEL_NOTE);
        verify(auditService).record(eq(7L), eq(AuditAction.REGISTRATION_CANCELLED), anyString(), eq(9L), any());
    }

    @Test
    void aDraftTournamentCannotBeStarted() {
        givenLocked(draft("rulebook-file"));
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(4L);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«borrador»");
    }

    /** A registration removed or created concurrently must be seen, so start takes the row lock. */
    @Test
    void startLoadsTheTournamentWithTheWriteLock() {
        givenLocked(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(2L);

        service.start(ORGANIZER, 1L);

        verify(tournaments).findByIdForUpdate(1L);
        verify(tournaments, never()).findById(any());
    }

    // --- finish -------------------------------------------------------------------------------

    @Test
    void finishIsRefusedBeforeTheEndDateWhileTheFinalHasNotBeenPlayed() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY.plusDays(5));
        givenLocked(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.finish(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha de cierre");
    }

    @Test
    void finishIsAllowedEarlyOnceTheFinalHasBeenPlayed() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY.plusDays(5));
        givenLocked(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(true);

        service.finish(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.FINISHED);
    }

    @Test
    void finishIsAllowedOnTheEndDate() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY);
        givenLocked(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);

        service.finish(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.FINISHED);
    }

    @Test
    void finishIsRefusedWhileAMatchIsStillScheduled() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY);
        givenLocked(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);
        when(finalMatch.hasScheduledMatches(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.finish(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("partidos programados");
        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.IN_PROGRESS);
        verify(auditService, never()).record(any(), eq(AuditAction.TOURNAMENT_FINISHED), any(), any(), any());
    }

    @Test
    void finishIsRefusedAfterTheFinalWhileAnotherMatchIsStillScheduled() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY.plusDays(5));
        givenLocked(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(true);
        when(finalMatch.hasScheduledMatches(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.finish(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("partidos programados");
        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.IN_PROGRESS);
    }

    /** Finishing serialises on the tournament row, like advance and fixture generation. */
    @Test
    void finishLoadsTheTournamentWithTheWriteLock() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY);
        givenLocked(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);

        service.finish(ORGANIZER, 1L);

        verify(tournaments).findByIdForUpdate(1L);
        verify(tournaments, never()).findById(any());
    }

    @Test
    void anActiveTournamentCannotBeFinished() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.setEndDate(TODAY);
        givenLocked(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.finish(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«activo»");
    }

    // --- current ------------------------------------------------------------------------------

    @Test
    void theCurrentTournamentIsTheOneInProgressEvenWhenAnActiveOneStartsLater() {
        Tournament inProgress = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        when(tournaments.findFirstByStatusOrderByStartDateDescIdDesc(TournamentStatus.IN_PROGRESS))
                .thenReturn(Optional.of(inProgress));

        assertThat(service.currentTournament()).contains(inProgress);
        verify(tournaments, never()).findFirstByStatusOrderByStartDateDescIdDesc(TournamentStatus.ACTIVE);
    }

    @Test
    void theCurrentTournamentFallsBackToTheLatestActiveOne() {
        Tournament active = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        when(tournaments.findFirstByStatusOrderByStartDateDescIdDesc(TournamentStatus.IN_PROGRESS))
                .thenReturn(Optional.empty());
        when(tournaments.findFirstByStatusOrderByStartDateDescIdDesc(TournamentStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        assertThat(service.currentTournament()).contains(active);
    }

    @Test
    void thereIsNoCurrentTournamentWithoutALiveOne() {
        when(tournaments.findFirstByStatusOrderByStartDateDescIdDesc(any())).thenReturn(Optional.empty());

        assertThat(service.currentTournament()).isEmpty();
    }

    // --- edits --------------------------------------------------------------------------------

    @Test
    void anActiveTournamentOnlyAcceptsDateChanges() {
        given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));

        assertThatThrownBy(() -> service.update(ORGANIZER, 1L,
                new UpdateTournamentRequest("New name", null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras esté en borrador");
    }

    @Test
    void anActiveTournamentMayMoveItsDates() {
        Tournament tournament = given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));

        service.update(ORGANIZER, 1L, new UpdateTournamentRequest(null, TODAY.plusDays(2), TODAY.plusDays(40),
                TODAY.plusDays(1), null, null));

        assertThat(tournament.getStartDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(tournament.getEndDate()).isEqualTo(TODAY.plusDays(40));
        assertThat(tournament.getRegistrationDeadline()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void dateChangesOnAnActiveTournamentAreStillValidated() {
        given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));

        assertThatThrownBy(() -> service.update(ORGANIZER, 1L,
                new UpdateTournamentRequest(null, null, null, TODAY.plusDays(20), null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha límite de inscripción");
    }

    @Test
    void anInProgressTournamentCannotBeUpdatedAtAll() {
        given(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));

        assertThatThrownBy(() -> service.update(ORGANIZER, 1L,
                new UpdateTournamentRequest(null, null, TODAY.plusDays(40), null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«en progreso»");
    }

    @Test
    void aDraftTournamentAcceptsEveryField() {
        Tournament tournament = given(draft("rulebook-file"));

        service.update(ORGANIZER, 1L, new UpdateTournamentRequest("New name", null, null, null, 12, null));

        assertThat(tournament.getName()).isEqualTo("New name");
        assertThat(tournament.getMaxTeams()).isEqualTo(12);
    }

    @Test
    void onlyADraftTournamentCanBeDeleted() {
        given(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));

        assertThatThrownBy(() -> service.delete(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras esté en borrador");
    }

    @Test
    void deletingADraftRemovesItsFilesAfterCommit() {
        Tournament tournament = given(draft("rulebook-file"));
        tournament.addVenue(Venue.builder().id(3L).name("Field").imageFileId("venue-image").build());
        tournament.addVenue(Venue.builder().id(4L).name("Gym").build());

        service.delete(ORGANIZER, 1L);

        verify(tournaments).delete(tournament);
        verify(fileDeletion).deleteAfterCommit("rulebook-file");
        verify(fileDeletion).deleteAfterCommit("venue-image");
        verify(fileDeletion).deleteAfterCommit(null);
    }

    @Test
    void updateRejectsAStartDateAfterTheEndDate() {
        given(draft("rulebook-file"));

        assertThatThrownBy(() -> service.update(ORGANIZER, 1L,
                new UpdateTournamentRequest(null, TODAY.plusDays(90), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha de cierre");
    }

    @Test
    void updateRejectsADeadlineAfterTheStartDate() {
        given(draft("rulebook-file"));

        assertThatThrownBy(() -> service.update(ORGANIZER, 1L,
                new UpdateTournamentRequest(null, null, null, TODAY.plusDays(20), null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha límite de inscripción");
    }

    @Test
    void updateRefusesAnEmptyPatch() {
        given(draft("rulebook-file"));

        assertThatThrownBy(() -> service.update(ORGANIZER, 1L,
                new UpdateTournamentRequest(null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No hay nada que actualizar");
    }

    // --- files --------------------------------------------------------------------------------

    @Test
    void replacingTheRulebookDeletesThePreviousOneAfterCommit() {
        Tournament tournament = given(draft("old-rulebook"));
        when(fileStorage.store(any(), eq(FileKind.PDF), eq(FileOwner.rulebook(1L)))).thenReturn("new-rulebook");

        service.uploadRulebook(ORGANIZER, 1L,
                new MockMultipartFile("file", "rules.pdf", "application/pdf", new byte[]{1, 2, 3}));

        assertThat(tournament.getRulebookFileId()).isEqualTo("new-rulebook");
        verify(fileDeletion).deleteAfterCommit("old-rulebook");
    }

    @Test
    void deletingAVenueDeletesItsImageAfterCommit() {
        Tournament tournament = given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        Venue venue = Venue.builder().id(3L).name("Field").imageFileId("venue-image").build();
        tournament.addVenue(venue);
        when(venues.findById(3L)).thenReturn(Optional.of(venue));

        service.deleteVenue(ORGANIZER, 1L, 3L);

        verify(venues).delete(venue);
        verify(fileDeletion).deleteAfterCommit("venue-image");
        verify(fileStorage, never()).delete(any());
    }

    // --- suspend / resume ----------------------------------------------------------------------

    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser(1L, "admin@escuelaing.edu.co", Set.of("ADMIN"));
    private static final AuthenticatedUser PLAYER =
            new AuthenticatedUser(20L, "player@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));

    @Test
    void suspendAndResumeRestoreExactlyThePreviousStatus() {
        for (TournamentStatus from : List.of(TournamentStatus.DRAFT, TournamentStatus.ACTIVE,
                TournamentStatus.IN_PROGRESS)) {
            Tournament tournament = withStatus(from, "rulebook-file");
            when(tournaments.findByIdForUpdate(1L)).thenReturn(Optional.of(tournament));

            var suspended = service.suspend(ADMIN, 1L);
            assertThat(suspended.status()).isEqualTo(TournamentStatus.SUSPENDED);
            assertThat(suspended.suspendedFromStatus()).isEqualTo(from);

            var resumed = service.resume(ORGANIZER, 1L);
            assertThat(resumed.status()).isEqualTo(from);
            assertThat(tournament.getSuspendedFromStatus()).isNull();
            verify(auditService).record(ADMIN.id(), AuditAction.TOURNAMENT_SUSPENDED, "TOURNAMENT", 1L,
                    Map.of("fromStatus", from.name()));
            verify(auditService).record(ORGANIZER.id(), AuditAction.TOURNAMENT_RESUMED, "TOURNAMENT", 1L,
                    Map.of("toStatus", from.name()));
        }
    }

    @Test
    void suspendLoadsTheTournamentWithTheWriteLock() {
        givenLocked(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));

        service.suspend(ORGANIZER, 1L);

        verify(tournaments, never()).findById(any());
    }

    @Test
    void aFinishedTournamentCannotBeSuspended() {
        givenLocked(withStatus(TournamentStatus.FINISHED, "rulebook-file"));

        assertThatThrownBy(() -> service.suspend(ADMIN, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«finalizado»");
    }

    @Test
    void aSuspendedTournamentCannotBeSuspendedAgainNorANonSuspendedOneResumed() {
        Tournament tournament = givenLocked(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));

        assertThatThrownBy(() -> service.resume(ADMIN, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no está suspendido");
        tournament.suspend();
        assertThatThrownBy(() -> service.suspend(ADMIN, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está suspendido");
    }

    @Test
    void aSuspendedTournamentRefusesEveryLifecycleAndEditAction() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.suspend();
        lenient().when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        lenient().when(tournaments.findByIdForUpdate(1L)).thenReturn(Optional.of(tournament));

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L)).hasMessageContaining("no se puede iniciar");
        assertThatThrownBy(() -> service.finish(ORGANIZER, 1L)).hasMessageContaining("no se puede finalizar");
        assertThatThrownBy(() -> service.activate(ORGANIZER, 1L)).hasMessageContaining("no se puede activar");
        assertThatThrownBy(() -> service.update(ORGANIZER, 1L,
                new UpdateTournamentRequest(null, null, TODAY.plusDays(40), null, null, null)))
                .hasMessageContaining("modificar sus datos");
        assertThatThrownBy(() -> service.uploadRulebook(ORGANIZER, 1L,
                new MockMultipartFile("file", "rules.pdf", "application/pdf", new byte[]{1})))
                .hasMessageContaining("cargar el reglamento");
        assertThatThrownBy(() -> service.createVenue(ORGANIZER, 1L, "Field", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("está suspendido")
                .hasMessageContaining("modificar sus canchas");
        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.SUSPENDED);
        verify(fileStorage, never()).store(any(), any(), any());
    }

    @Test
    void suspendedTournamentsAreHiddenFromThePublicListButNotFromStaff() {
        Tournament visible = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        Tournament suspended = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        suspended.setId(2L);
        suspended.suspend();
        when(tournaments.findAllByStatusNotOrderByStartDateDescIdDesc(TournamentStatus.SUSPENDED))
                .thenReturn(List.of(visible));
        when(tournaments.findAllByOrderByStartDateDescIdDesc()).thenReturn(List.of(visible, suspended));

        assertThat(service.list(null)).extracting("id").containsExactly(1L);
        assertThat(service.list(PLAYER)).extracting("id").containsExactly(1L);
        assertThat(service.list(ORGANIZER)).extracting("id").containsExactly(1L, 2L);
        assertThat(service.list(ADMIN)).extracting("id").containsExactly(1L, 2L);
    }

    @Test
    void aSuspendedTournamentIsNotFoundForThePublic() {
        Tournament tournament = given(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));
        tournament.suspend();

        assertThatThrownBy(() -> service.get(1L, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(1L, PLAYER)).isInstanceOf(NotFoundException.class);
        assertThat(service.get(1L, ORGANIZER).status()).isEqualTo(TournamentStatus.SUSPENDED);
    }

    /** Matches, tables, bracket, statistics, groups and venues all go through requireVisible. */
    @Test
    void everyPublicReadOfASuspendedTournamentIsNotFoundForThePublic() {
        Tournament tournament = given(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));
        tournament.suspend();

        assertThatThrownBy(() -> service.requireVisible(1L, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.requireVisible(1L, PLAYER)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.venuesOf(1L, PLAYER)).isInstanceOf(NotFoundException.class);
        assertThat(service.requireVisible(1L, ORGANIZER)).isSameAs(tournament);
        assertThat(service.requireVisible(1L, ADMIN)).isSameAs(tournament);
    }

    @Test
    void aTournamentThatIsNotSuspendedIsVisibleToEverybody() {
        Tournament tournament = given(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));

        assertThat(service.requireVisible(1L, null)).isSameAs(tournament);
    }

    @Test
    void aSuspendedTournamentIsNeverTheCurrentOne() {
        when(tournaments.findFirstByStatusOrderByStartDateDescIdDesc(any())).thenReturn(Optional.empty());

        assertThat(service.currentTournament()).isEmpty();
        verify(tournaments, never()).findFirstByStatusOrderByStartDateDescIdDesc(TournamentStatus.SUSPENDED);
    }

    // --- hard delete ----------------------------------------------------------------------------

    @Test
    void onlyAnAdminCanHardDeleteATournament() {
        assertThatThrownBy(() -> service.hardDelete(ORGANIZER, 1L, "TechCup 2026-1"))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(tournaments, never()).delete(any());
    }

    @Test
    void hardDeleteRequiresTheExactTournamentName() {
        givenLocked(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));

        for (String wrong : new String[]{null, "", "techcup 2026-1", "TechCup"}) {
            assertThatThrownBy(() -> service.hardDelete(ADMIN, 1L, wrong))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("escriba exactamente el nombre del torneo («TechCup 2026-1»)");
        }
        verify(tournaments, never()).delete(any());
        verify(fileDeletion, never()).deleteAfterCommit(anyString());
    }

    @Test
    void hardDeleteRemovesTheTournamentInAnyStatusAndItsFilesAfterCommit() {
        Tournament tournament = givenLocked(withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file"));
        tournament.addVenue(Venue.builder().id(3L).name("Field").imageFileId("venue-image").build());
        when(registrations.findReceiptFileIdsByTournamentId(1L)).thenReturn(List.of("receipt-1", "receipt-2"));

        service.hardDelete(ADMIN, 1L, "  TechCup 2026-1 ");

        verify(tournaments).delete(tournament);
        verify(fileDeletion).deleteAfterCommit("rulebook-file");
        verify(fileDeletion).deleteAfterCommit("venue-image");
        verify(fileDeletion).deleteAfterCommit("receipt-1");
        verify(fileDeletion).deleteAfterCommit("receipt-2");
        verify(fileStorage, never()).delete(any());
        verify(auditService).record(eq(ADMIN.id()), eq(AuditAction.TOURNAMENT_DELETED), eq("TOURNAMENT"), eq(1L),
                any(Map.class));
    }

    @Test
    void aSuspendedTournamentCanBeHardDeleted() {
        Tournament tournament = givenLocked(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        tournament.suspend();

        service.hardDelete(ADMIN, 1L, "TechCup 2026-1");

        verify(tournaments).delete(tournament);
    }

    // --- helpers ------------------------------------------------------------------------------

    private Tournament givenLocked(Tournament tournament) {
        when(tournaments.findByIdForUpdate(1L)).thenReturn(Optional.of(tournament));
        return tournament;
    }

    private Tournament given(Tournament tournament) {
        when(tournaments.findById(1L)).thenReturn(Optional.of(tournament));
        return tournament;
    }

    private static Tournament draft(String rulebookFileId) {
        return withStatus(TournamentStatus.DRAFT, rulebookFileId);
    }

    private static Tournament withStatus(TournamentStatus status, String rulebookFileId) {
        return Tournament.builder()
                .id(1L)
                .name("TechCup 2026-1")
                .startDate(TODAY)
                .endDate(TODAY.plusDays(30))
                .registrationDeadline(TODAY.minusDays(5))
                .maxTeams(8)
                .fee(BigDecimal.TEN)
                .status(status)
                .rulebookFileId(rulebookFileId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}

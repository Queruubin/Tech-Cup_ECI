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
        given(tournament);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha de inicio");
    }

    @Test
    void startIsAllowedAfterTheStartDateWhileTheEndDateHasNotPassed() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.setStartDate(TODAY.minusDays(3));
        given(tournament);
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(2L);

        service.start(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.IN_PROGRESS);
    }

    @Test
    void startIsRefusedAfterTheEndDate() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.setStartDate(TODAY.minusDays(40));
        tournament.setEndDate(TODAY.minusDays(1));
        given(tournament);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha de cierre");
    }

    @Test
    void startNeedsTwoApprovedRegistrations() {
        given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(1L);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inscripciones aprobadas");
    }

    @Test
    void startMovesActiveToInProgressOnTheStartDate() {
        Tournament tournament = given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(2L);

        service.start(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.IN_PROGRESS);
    }

    @Test
    void startCancelsTheRegistrationsStillUnderReview() {
        Tournament tournament = given(withStatus(TournamentStatus.ACTIVE, "rulebook-file"));
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
        given(draft("rulebook-file"));
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(4L);

        assertThatThrownBy(() -> service.start(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«borrador»");
    }

    // --- finish -------------------------------------------------------------------------------

    @Test
    void finishIsRefusedBeforeTheEndDateWhileTheFinalHasNotBeenPlayed() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY.plusDays(5));
        given(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.finish(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha de cierre");
    }

    @Test
    void finishIsAllowedEarlyOnceTheFinalHasBeenPlayed() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY.plusDays(5));
        given(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(true);

        service.finish(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.FINISHED);
    }

    @Test
    void finishIsAllowedOnTheEndDate() {
        Tournament tournament = withStatus(TournamentStatus.IN_PROGRESS, "rulebook-file");
        tournament.setEndDate(TODAY);
        given(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);

        service.finish(ORGANIZER, 1L);

        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.FINISHED);
    }

    @Test
    void anActiveTournamentCannotBeFinished() {
        Tournament tournament = withStatus(TournamentStatus.ACTIVE, "rulebook-file");
        tournament.setEndDate(TODAY);
        given(tournament);
        when(finalMatch.isFinalMatchPlayed(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.finish(ORGANIZER, 1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«activo»");
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

    // --- helpers ------------------------------------------------------------------------------

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

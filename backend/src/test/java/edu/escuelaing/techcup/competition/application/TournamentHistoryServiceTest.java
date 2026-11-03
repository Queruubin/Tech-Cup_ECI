package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.PodiumPhoto;
import edu.escuelaing.techcup.competition.domain.SanctionedTeam;
import edu.escuelaing.techcup.competition.domain.SanctionedTeamMember;
import edu.escuelaing.techcup.competition.infrastructure.MatchEventRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.competition.infrastructure.PodiumPhotoRepository;
import edu.escuelaing.techcup.competition.infrastructure.SanctionedTeamRepository;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.InvalidRequestException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.teams.application.TeamRosterEntry;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/** The history of a finished tournament: aggregation, sanctioned teams (snapshot) and podium photos. */
@ExtendWith(MockitoExtension.class)
class TournamentHistoryServiceTest {

    private static final AuthenticatedUser ORGANIZER =
            new AuthenticatedUser(7L, "organizer@escuelaing.edu.co", Set.of("ORGANIZER"));
    private static final Team TIGERS = team(1L, "Tigers");
    private static final Team LIONS = team(2L, "Lions");
    private static final Team PUMAS = team(3L, "Pumas");
    private static final Team BEARS = team(4L, "Bears");

    @Mock
    private MatchRepository matches;
    @Mock
    private MatchEventRepository events;
    @Mock
    private SanctionedTeamRepository sanctions;
    @Mock
    private PodiumPhotoRepository podiumPhotos;
    @Mock
    private TournamentService tournamentService;
    @Mock
    private TeamService teamService;
    @Mock
    private StandingsService standingsService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private FileDeletionScheduler fileDeletion;
    @Mock
    private AuditService auditService;

    private Tournament tournament;
    private TournamentHistoryService service;

    @BeforeEach
    void setUp() {
        service = new TournamentHistoryService(matches, events, sanctions, podiumPhotos, tournamentService, teamService,
                standingsService, fileStorage, fileDeletion, auditService);
        tournament = Tournament.builder().id(1L).name("TechCup 2026-1").startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 5, 30)).registrationDeadline(LocalDate.of(2026, 2, 20)).maxTeams(8)
                .fee(BigDecimal.TEN).status(TournamentStatus.FINISHED).build();
        lenient().when(tournamentService.requireVisible(eq(1L), any())).thenReturn(tournament);
        lenient().when(tournamentService.requireTournamentForUpdate(1L)).thenReturn(tournament);
        lenient().when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L)).thenReturn(bracket());
        lenient().when(standingsService.rows(1L)).thenReturn(List.of());
        lenient().when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(1L, 2L, 3L, 4L));
    }

    // --- reads ----------------------------------------------------------------------------------

    @Test
    void anAnonymousVisitorReadsTheHistoryOfAFinishedTournament() {
        when(podiumPhotos.findByTournamentIdOrderByPositionAsc(1L)).thenReturn(List.of(
                PodiumPhoto.builder().tournamentId(1L).position(1).teamId(1L).fileId("photo-1").build()));
        when(events.findTopScorers(1L)).thenReturn(List.<Object[]>of(
                new Object[]{101L, "Ana", 1L, "Tigers", 3L}, new Object[]{201L, "Zoe", 2L, "Lions", 3L},
                new Object[]{301L, "Luis", 3L, "Pumas", 1L}));

        var history = service.history(1L, null);

        assertThat(history.podiumSource()).isEqualTo("KNOCKOUT");
        // Bears (0-1 in their semi-final) beat Pumas (0-2) to the third place on goal difference.
        assertThat(history.podium()).extracting(entry -> entry.teamName()).containsExactly("Tigers", "Lions", "Bears");
        assertThat(history.podium().get(0).photoFileId()).isEqualTo("photo-1");
        assertThat(history.podium().get(1).photoFileId()).isNull();
        assertThat(history.thirdPlaceCandidates()).extracting(candidate -> candidate.teamName())
                .containsExactly("Bears", "Pumas");
        assertThat(history.topScorers()).extracting(scorer -> scorer.playerName()).containsExactly("Ana", "Zoe");
        assertThat(history.bestDefence()).isNotNull();
        assertThat(history.participants()).extracting(team -> team.name())
                .containsExactly("Bears", "Lions", "Pumas", "Tigers");
    }

    @Test
    void theHistoryExistsOnlyForFinishedTournaments() {
        tournament.setStatus(TournamentStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.history(1L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El historial solo está disponible para torneos finalizados; el estado de «TechCup 2026-1» "
                        + "es «en progreso».");
    }

    /** The snapshot keeps the name and the members after the team was deleted (team id set to null by the FK). */
    @Test
    void aSanctionedTeamStillShowsAfterItsTeamWasDeleted() {
        SanctionedTeam snapshot = SanctionedTeam.builder().id(9L).tournamentId(1L).teamId(null).teamName("Old Name")
                .note("Indisciplina").build();
        snapshot.addMember(SanctionedTeamMember.builder().userId(null).fullName("Zoe Ruiz").captain(false).build());
        snapshot.addMember(SanctionedTeamMember.builder().userId(55L).fullName("Ana Gil").captain(true).build());
        when(sanctions.findByTournamentIdOrderByCreatedAtAscIdAsc(1L)).thenReturn(List.of(snapshot));

        var history = service.history(1L, null);

        assertThat(history.sanctionedTeams()).singleElement().satisfies(entry -> {
            assertThat(entry.teamId()).isNull();
            assertThat(entry.teamName()).isEqualTo("Old Name");
            assertThat(entry.note()).isEqualTo("Indisciplina");
            assertThat(entry.members()).extracting(member -> member.fullName()).containsExactly("Ana Gil", "Zoe Ruiz");
            assertThat(entry.members().get(0).captain()).isTrue();
        });
    }

    @Test
    void theListOfPastTournamentsNamesEachChampion() {
        when(tournamentService.finishedTournaments()).thenReturn(List.of(tournament));

        var list = service.finished();

        assertThat(list).singleElement().satisfies(summary -> {
            assertThat(summary.name()).isEqualTo("TechCup 2026-1");
            assertThat(summary.champion().teamName()).isEqualTo("Tigers");
        });
    }

    // --- sanctioned teams -----------------------------------------------------------------------

    @Test
    void sanctioningATeamCopiesItsNameAndMembers() {
        when(sanctions.existsByTournamentIdAndTeamId(1L, 3L)).thenReturn(false);
        when(teamService.rosterOf(3L)).thenReturn(List.of(
                new TeamRosterEntry(3L, "Pumas", user(31L, "Carla Díaz"), true, null, 10),
                new TeamRosterEntry(3L, "Pumas", user(32L, "Bruno Paz"), false, null, 7)));
        List<SanctionedTeam> saved = new ArrayList<>();
        when(sanctions.saveAndFlush(any())).thenAnswer(invocation -> {
            SanctionedTeam sanction = invocation.getArgument(0);
            sanction.setId(9L);
            saved.add(sanction);
            return sanction;
        });

        var response = service.addSanction(ORGANIZER, 1L, 3L, "  Agresión al árbitro  ");

        assertThat(response.teamName()).isEqualTo("Pumas");
        assertThat(response.note()).isEqualTo("Agresión al árbitro");
        assertThat(response.members()).extracting(member -> member.fullName()).containsExactly("Carla Díaz", "Bruno Paz");
        assertThat(saved.get(0).getCreatedBy()).isEqualTo(7L);
        assertThat(saved.get(0).getMembers()).extracting(SanctionedTeamMember::getUserId).containsExactly(31L, 32L);
        verify(tournamentService).requireTournamentForUpdate(1L);
        verify(auditService).record(eq(7L), eq(AuditAction.TOURNAMENT_TEAM_SANCTIONED), eq("TOURNAMENT"), eq(1L),
                argThat(details -> details.get("teamId").equals(3L) && details.get("members").equals(2)));
    }

    @Test
    void aTeamIsSanctionedOncePerTournament() {
        when(sanctions.existsByTournamentIdAndTeamId(1L, 3L)).thenReturn(true);

        assertThatThrownBy(() -> service.addSanction(ORGANIZER, 1L, 3L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Pumas' ya figura como sancionado en este torneo.");
        verify(sanctions, never()).saveAndFlush(any());
    }

    @Test
    void onlyATeamThatTookPartCanBeSanctionedAndOnlyOnceTheTournamentFinished() {
        when(teamService.requireTeam(99L)).thenReturn(team(99L, "Outsiders"));
        assertThatThrownBy(() -> service.addSanction(ORGANIZER, 1L, 99L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Outsiders' no participó en el torneo «TechCup 2026-1».");

        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.addSanction(ORGANIZER, 1L, 3L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("solo está disponible para torneos finalizados");
        verify(sanctions, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void aSanctionIsRemovedOnlyFromItsOwnTournament() {
        SanctionedTeam sanction = SanctionedTeam.builder().id(9L).tournamentId(1L).teamId(3L).teamName("Pumas").build();
        when(sanctions.findByIdAndTournamentId(9L, 1L)).thenReturn(Optional.of(sanction));
        when(sanctions.findByIdAndTournamentId(10L, 1L)).thenReturn(Optional.empty());

        service.removeSanction(ORGANIZER, 1L, 9L);

        verify(sanctions).delete(sanction);
        verify(auditService).record(eq(7L), eq(AuditAction.TOURNAMENT_SANCTION_REMOVED), eq("TOURNAMENT"), eq(1L),
                argThat(details -> details.get("sanctionId").equals(9L)));
        assertThatThrownBy(() -> service.removeSanction(ORGANIZER, 1L, 10L)).isInstanceOf(NotFoundException.class);
    }

    // --- podium photos --------------------------------------------------------------------------

    @Test
    void aPodiumPhotoIsStoredAsAPodiumImageAndReplacesThePreviousOneAfterCommit() {
        PodiumPhoto existing = PodiumPhoto.builder().id(5L).tournamentId(1L).position(1).teamId(1L)
                .fileId("old-photo").build();
        when(podiumPhotos.findByTournamentIdAndPosition(1L, 1)).thenReturn(Optional.of(existing));
        when(fileStorage.store(any(), eq(FileKind.IMAGE), eq(FileOwner.podium(1L)))).thenReturn("new-photo");

        var response = service.replacePodiumPhoto(ORGANIZER, 1L, 1, image());

        assertThat(response.fileId()).isEqualTo("new-photo");
        assertThat(response.teamId()).isEqualTo(1L);
        assertThat(existing.getFileId()).isEqualTo("new-photo");
        assertThat(existing.getUploadedBy()).isEqualTo(7L);
        verify(podiumPhotos).save(existing);
        verify(fileDeletion).deleteAfterCommit("old-photo");
        verify(auditService).record(eq(7L), eq(AuditAction.PODIUM_PHOTO_UPDATED), eq("TOURNAMENT"), eq(1L),
                argThat(details -> "old-photo".equals(details.get("previousFileId"))));
    }

    @Test
    void aPodiumPhotoNeedsAValidPositionWithATeamAndAFinishedTournament() {
        assertThatThrownBy(() -> service.replacePodiumPhoto(ORGANIZER, 1L, 4, image()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("La posición del podio debe ser 1, 2 o 3.");

        // A final without semi-finals has no third place.
        when(matches.findByTournamentIdOrderByScheduledAtAscIdAsc(1L))
                .thenReturn(List.of(played(70L, MatchPhase.FINAL, TIGERS, LIONS, 1, 0)));
        assertThatThrownBy(() -> service.replacePodiumPhoto(ORGANIZER, 1L, 3, image()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El torneo «TechCup 2026-1» no tiene un equipo en el 3° puesto.");

        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.replacePodiumPhoto(ORGANIZER, 1L, 1, image()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("solo está disponible para torneos finalizados");
        verifyNoInteractions(fileStorage, fileDeletion);
    }

    @Test
    void removingAPodiumPhotoDeletesItsFileAfterCommit() {
        PodiumPhoto existing = PodiumPhoto.builder().id(5L).tournamentId(1L).position(2).teamId(2L)
                .fileId("photo-2").build();
        when(podiumPhotos.findByTournamentIdAndPosition(1L, 2)).thenReturn(Optional.of(existing));
        when(podiumPhotos.findByTournamentIdAndPosition(1L, 3)).thenReturn(Optional.empty());

        service.removePodiumPhoto(ORGANIZER, 1L, 2);

        verify(podiumPhotos).delete(existing);
        verify(fileDeletion).deleteAfterCommit("photo-2");
        verify(auditService).record(eq(7L), eq(AuditAction.PODIUM_PHOTO_REMOVED), eq("TOURNAMENT"), eq(1L), any());
        assertThatThrownBy(() -> service.removePodiumPhoto(ORGANIZER, 1L, 3))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El 3° puesto no tiene foto.");
    }

    @Test
    void readingAMissingPodiumPhotoIsNotFound() {
        when(podiumPhotos.findByTournamentIdAndPosition(1L, 2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.podiumPhoto(1L, 2, null)).isInstanceOf(NotFoundException.class);
    }

    // --- helpers --------------------------------------------------------------------------------

    /** SF Tigers 2-0 Pumas, SF Lions 1-0 Bears, final Tigers 3-1 Lions. */
    private static List<Match> bracket() {
        return List.of(played(60L, MatchPhase.SEMIFINAL, TIGERS, PUMAS, 2, 0),
                played(61L, MatchPhase.SEMIFINAL, LIONS, BEARS, 1, 0),
                played(70L, MatchPhase.FINAL, TIGERS, LIONS, 3, 1));
    }

    private static Match played(Long id, MatchPhase phase, Team home, Team away, int homeScore, int awayScore) {
        return Match.builder().id(id).phase(phase).roundNumber(1).homeTeam(home).awayTeam(away)
                .status(MatchStatus.PLAYED).homeScore(homeScore).awayScore(awayScore).build();
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "podio.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    }

    private static AppUser user(Long id, String name) {
        return AppUser.builder().id(id).fullName(name).build();
    }

    private static Team team(Long id, String name) {
        return Team.builder().id(id).name(name).colors("blue")
                .captain(AppUser.builder().id(id * 100).fullName("Captain " + id).build())
                .status(TeamStatus.ACTIVE).build();
    }
}

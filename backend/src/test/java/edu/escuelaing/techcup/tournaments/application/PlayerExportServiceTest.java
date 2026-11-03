package edu.escuelaing.techcup.tournaments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.PlayerAgePolicy;
import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.DocumentType;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.application.TeamRosterEntry;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.infrastructure.RegistrationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The tournament player export: ADMIN only, any status, one row per member of the approved teams
 * (and a second sheet for the teams under review), audited.
 */
@ExtendWith(MockitoExtension.class)
class PlayerExportServiceTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin@escuelaing.edu.co", Set.of("ADMIN"));
    private static final AuthenticatedUser ORGANIZER =
            new AuthenticatedUser(7L, "organizer@escuelaing.edu.co", Set.of("ORGANIZER"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    @Mock
    private TournamentService tournamentService;
    @Mock
    private RegistrationRepository registrations;
    @Mock
    private TeamService teamService;
    @Mock
    private PlayerSpreadsheetWriter writer;
    @Mock
    private AuditService auditService;

    private PlayerExportService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        PlayerAgePolicy agePolicy = new PlayerAgePolicy(5, 100, clock);
        service = new PlayerExportService(tournamentService, registrations, teamService, agePolicy, writer, auditService);
    }

    @Test
    void onlyAnAdminExports() {
        assertThatThrownBy(() -> service.export(ORGANIZER, 1L)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.export(null, 1L)).isInstanceOf(ForbiddenOperationException.class);
        verify(tournamentService, never()).requireTournament(any());
        verify(writer, never()).write(any());
    }

    @Test
    void anUnknownTournamentIsNotFound() {
        when(tournamentService.requireTournament(9L)).thenThrow(NotFoundException.of("el torneo", 9L));

        assertThatThrownBy(() -> service.export(ADMIN, 9L)).isInstanceOf(NotFoundException.class);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void writesOneRowPerApprovedPlayerWithCaptainFlagLabelsAndDates() {
        givenTournament("Copa Ñandú 2026-2", TournamentStatus.IN_PROGRESS);
        Team lions = team(6L, "Lions");
        Team tigers = team(5L, "Tigers");
        when(registrations.findByTournamentIdAndStatusOrderByIdAsc(1L, RegistrationStatus.APPROVED))
                .thenReturn(List.of(registration(tigers), registration(lions)));
        when(registrations.findByTournamentIdAndStatusOrderByIdAsc(1L, RegistrationStatus.UNDER_REVIEW))
                .thenReturn(List.of());
        AppUser ana = student(20L, "Ana Diaz", LocalDate.of(2004, 11, 2));
        ana.setPhoneNumber("+573001234567");
        AppUser beto = student(21L, "Beto Ruiz", LocalDate.of(2003, 1, 15));
        AppUser carla = AppUser.builder().id(30L).fullName("Carla Paz").email("carla@mail.com")
                .documentType(DocumentType.PASSPORT).documentNumber("X1").birthDate(LocalDate.of(1990, 5, 5))
                .academicProgram(AcademicProgram.OTHER).build();
        when(teamService.rosterOf(5L)).thenReturn(List.of(
                new TeamRosterEntry(5L, "Tigers", beto, false, Position.DEFENDER, 4),
                new TeamRosterEntry(5L, "Tigers", ana, true, Position.FORWARD, 9)));
        when(teamService.rosterOf(6L)).thenReturn(List.of(new TeamRosterEntry(6L, "Lions", carla, true, null, null)));
        when(writer.extension()).thenReturn("xlsx");
        when(writer.contentType()).thenReturn("application/x");
        when(writer.write(any())).thenReturn(new byte[]{9});

        PlayerExport export = service.export(ADMIN, 1L);

        assertThat(export.filename()).isEqualTo("jugadores-copa-nandu-2026-2.xlsx");
        assertThat(export.content()).containsExactly(9);
        List<PlayerExportSheet> sheets = capturedSheets();
        assertThat(sheets).extracting(PlayerExportSheet::name).containsExactly("Aprobados", "En revisión");
        List<PlayerExportRow> rows = sheets.get(0).rows();
        // Team name order, captain first inside each team.
        assertThat(rows).extracting(PlayerExportRow::fullName).containsExactly("Carla Paz", "Ana Diaz", "Beto Ruiz");
        PlayerExportRow captain = rows.get(1);
        assertThat(captain.captain()).isTrue();
        assertThat(captain.teamName()).isEqualTo("Tigers");
        assertThat(captain.email()).isEqualTo("ana@escuelaing.edu.co");
        assertThat(captain.phoneNumber()).isEqualTo("+573001234567");
        assertThat(captain.documentType()).isEqualTo("Cédula de ciudadanía");
        assertThat(captain.documentNumber()).isEqualTo("1020");
        assertThat(captain.birthDate()).isEqualTo(LocalDate.of(2004, 11, 2));
        assertThat(captain.age()).isEqualTo(21);
        assertThat(captain.schoolRelation()).isEqualTo("Estudiante");
        assertThat(captain.academicProgram()).isEqualTo("Ingeniería de Sistemas");
        assertThat(captain.semester()).isEqualTo(6);
        assertThat(captain.position()).isEqualTo("Delantero");
        assertThat(captain.jerseyNumber()).isEqualTo(9);
        assertThat(captain.registrationStatus()).isEqualTo("Aprobada");
        assertThat(rows.get(2).captain()).isFalse();
        PlayerExportRow withoutRelation = rows.get(0);
        assertThat(withoutRelation.schoolRelation()).isNull();
        assertThat(withoutRelation.phoneNumber()).isNull();
        assertThat(withoutRelation.semester()).isNull();
        assertThat(withoutRelation.position()).isNull();
        assertThat(withoutRelation.jerseyNumber()).isNull();
        assertThat(withoutRelation.documentType()).isEqualTo("Pasaporte");
        assertThat(withoutRelation.academicProgram()).isEqualTo("Otro");
        verify(auditService).record(1L, AuditAction.PLAYERS_EXPORTED, "TOURNAMENT", 1L,
                Map.of("approvedPlayers", 3, "underReviewPlayers", 0));
    }

    @Test
    void teamsUnderReviewGoToTheSecondSheet() {
        givenTournament("TechCup", TournamentStatus.ACTIVE);
        Team tigers = team(5L, "Tigers");
        when(registrations.findByTournamentIdAndStatusOrderByIdAsc(1L, RegistrationStatus.APPROVED)).thenReturn(List.of());
        when(registrations.findByTournamentIdAndStatusOrderByIdAsc(1L, RegistrationStatus.UNDER_REVIEW))
                .thenReturn(List.of(registration(tigers)));
        when(teamService.rosterOf(5L)).thenReturn(List.of(new TeamRosterEntry(5L, "Tigers",
                student(20L, "Ana Diaz", LocalDate.of(2004, 1, 1)), true, Position.FORWARD, 9)));
        when(writer.extension()).thenReturn("xlsx");
        when(writer.write(any())).thenReturn(new byte[0]);

        service.export(ADMIN, 1L);

        List<PlayerExportSheet> sheets = capturedSheets();
        assertThat(sheets.get(0).rows()).isEmpty();
        assertThat(sheets.get(1).rows()).singleElement()
                .satisfies(row -> assertThat(row.registrationStatus()).isEqualTo("En revisión"));
    }

    @Test
    void aTournamentWithoutTeamsInAnyStatusExportsEmptySheets() {
        for (TournamentStatus status : TournamentStatus.values()) {
            givenTournament("TechCup", status);
            when(registrations.findByTournamentIdAndStatusOrderByIdAsc(any(), any())).thenReturn(List.of());
            when(writer.extension()).thenReturn("xlsx");
            when(writer.write(any())).thenReturn(new byte[]{1});

            assertThat(service.export(ADMIN, 1L).filename()).isEqualTo("jugadores-techcup.xlsx");
        }
        verify(teamService, never()).rosterOf(any());
    }

    @Test
    void theSlugFallsBackToTheIdWhenTheNameHasNothingUsable() {
        assertThat(PlayerExportService.slug("¡¡¡", 4L)).isEqualTo("torneo-4");
        assertThat(PlayerExportService.slug("  Copa  Ingeniería — 2026/1 ", 4L)).isEqualTo("copa-ingenieria-2026-1");
        assertThat(PlayerExportService.slug("a".repeat(80), 4L)).hasSize(60);
    }

    @SuppressWarnings("unchecked")
    private List<PlayerExportSheet> capturedSheets() {
        ArgumentCaptor<List<PlayerExportSheet>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer, org.mockito.Mockito.atLeastOnce()).write(captor.capture());
        return captor.getValue();
    }

    private void givenTournament(String name, TournamentStatus status) {
        when(tournamentService.requireTournament(1L))
                .thenReturn(Tournament.builder().id(1L).name(name).status(status).build());
    }

    private static Team team(Long id, String name) {
        return Team.builder().id(id).name(name).build();
    }

    private static Registration registration(Team team) {
        return Registration.builder().team(team).build();
    }

    private static AppUser student(Long id, String name, LocalDate birthDate) {
        return AppUser.builder().id(id).fullName(name).email(name.split(" ")[0].toLowerCase() + "@escuelaing.edu.co")
                .documentType(DocumentType.CC).documentNumber("10" + id).birthDate(birthDate)
                .schoolRelation(SchoolRelation.STUDENT).academicProgram(AcademicProgram.SYSTEMS_ENGINEERING)
                .semester(6).build();
    }
}

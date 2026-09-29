package edu.escuelaing.techcup.tournaments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.tournaments.domain.Registration;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import edu.escuelaing.techcup.tournaments.infrastructure.RegistrationRepository;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * The registration lifecycle: who may register, when, the capacity, the eligibility gate and the
 * review decisions on top of the {@code RegistrationStatus} State machine.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final AuthenticatedUser CAPTAIN =
            new AuthenticatedUser(20L, "captain@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser OTHER_MEMBER =
            new AuthenticatedUser(21L, "player@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser ORGANIZER =
            new AuthenticatedUser(7L, "organizer@escuelaing.edu.co", Set.of("ORGANIZER"));
    private static final MultipartFile RECEIPT =
            new MockMultipartFile("file", "receipt.pdf", "application/pdf", new byte[]{1, 2, 3});

    @Mock
    private RegistrationRepository registrations;
    @Mock
    private TournamentService tournamentService;
    @Mock
    private TeamService teamService;
    @Mock
    private UserService userService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private AuditService auditService;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        service = new RegistrationService(registrations, tournamentService, teamService, userService,
                fileStorage, auditService, clock);
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    void registrationRequiresAnActiveTournament() {
        givenTournament(TournamentStatus.DRAFT, TODAY.plusDays(5), 8);

        assertThatThrownBy(() -> service.register(CAPTAIN, 1L, RECEIPT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté activo");
        verify(registrations, never()).save(any());
    }

    @Test
    void registrationIsRefusedAfterTheDeadline() {
        givenTournament(TournamentStatus.ACTIVE, TODAY.minusDays(1), 8);

        assertThatThrownBy(() -> service.register(CAPTAIN, 1L, RECEIPT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fecha límite de inscripción");
    }

    @Test
    void registrationIsAllowedOnTheDeadlineItself() {
        givenTournament(TournamentStatus.ACTIVE, TODAY, 8);
        givenTeamOfCaptain();
        givenNoLiveRegistration();
        givenApprovedCount(0L);
        givenEligible(true);
        when(fileStorage.store(any(), eq(FileKind.IMAGE_OR_PDF), eq(FileOwner.receipt(5L)))).thenReturn("receipt-file");
        when(registrations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.register(CAPTAIN, 1L, RECEIPT);

        assertThat(response.status()).isEqualTo(RegistrationStatus.UNDER_REVIEW);
        assertThat(response.receiptFileId()).isEqualTo("receipt-file");
    }

    @Test
    void onlyTheCaptainMayRegisterTheTeam() {
        givenTournament(TournamentStatus.ACTIVE, TODAY.plusDays(5), 8);
        when(teamService.findActiveTeamOf(OTHER_MEMBER.id())).thenReturn(Optional.of(team()));

        assertThatThrownBy(() -> service.register(OTHER_MEMBER, 1L, RECEIPT))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("capitán");
    }

    @Test
    void registrationRequiresAnActiveTeam() {
        givenTournament(TournamentStatus.ACTIVE, TODAY.plusDays(5), 8);
        when(teamService.findActiveTeamOf(CAPTAIN.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(CAPTAIN, 1L, RECEIPT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("equipo activo");
    }

    @Test
    void aTeamCannotRegisterTwiceWhileTheFirstAttemptIsLive() {
        givenTournament(TournamentStatus.ACTIVE, TODAY.plusDays(5), 8);
        givenTeamOfCaptain();
        when(registrations.findFirstByTournamentIdAndTeamIdAndStatusInOrderByIdDesc(eq(1L), eq(5L), anyCollection()))
                .thenReturn(Optional.of(registration(RegistrationStatus.UNDER_REVIEW)));

        assertThatThrownBy(() -> service.register(CAPTAIN, 1L, RECEIPT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inscripción en revisión");
    }

    @Test
    void registrationIsRefusedWhenTheTournamentIsFull() {
        givenTournament(TournamentStatus.ACTIVE, TODAY.plusDays(5), 4);
        givenTeamOfCaptain();
        givenNoLiveRegistration();
        givenApprovedCount(4L);

        assertThatThrownBy(() -> service.register(CAPTAIN, 1L, RECEIPT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está completo");
    }

    @Test
    void registrationIsRefusedWhenTheTeamIsNotEligible() {
        givenTournament(TournamentStatus.ACTIVE, TODAY.plusDays(5), 8);
        givenTeamOfCaptain();
        givenNoLiveRegistration();
        givenApprovedCount(0L);
        givenEligible(false);

        assertThatThrownBy(() -> service.register(CAPTAIN, 1L, RECEIPT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no cumple los requisitos")
                .hasMessageContaining("al menos 7");
        verify(fileStorage, never()).store(any(), any(), any());
    }

    // --- review -------------------------------------------------------------------------------

    @Test
    void approvingMovesTheRegistrationToApproved() {
        Registration registration = registration(RegistrationStatus.UNDER_REVIEW);
        when(registrations.findById(9L)).thenReturn(Optional.of(registration));
        givenLockedTournament(TournamentStatus.ACTIVE);
        givenApprovedCount(1L);
        when(userService.getUser(ORGANIZER.id())).thenReturn(user(ORGANIZER.id()));

        var response = service.approve(ORGANIZER, 9L, "Payment verified");

        assertThat(response.status()).isEqualTo(RegistrationStatus.APPROVED);
        assertThat(registration.getReviewNote()).isEqualTo("Payment verified");
        assertThat(registration.getReviewedBy()).isNotNull();
        verify(tournamentService).requireTournamentForUpdate(1L);
    }

    @Test
    void approvingRechecksTheCapacity() {
        when(registrations.findById(9L)).thenReturn(Optional.of(registration(RegistrationStatus.UNDER_REVIEW)));
        givenLockedTournament(TournamentStatus.ACTIVE);
        givenApprovedCount(8L);

        assertThatThrownBy(() -> service.approve(ORGANIZER, 9L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya está completo");
    }

    @Test
    void approvingRequiresAnActiveTournament() {
        Registration registration = registration(RegistrationStatus.UNDER_REVIEW);
        when(registrations.findById(9L)).thenReturn(Optional.of(registration));
        givenLockedTournament(TournamentStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.approve(ORGANIZER, 9L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mientras el torneo esté activo");
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.UNDER_REVIEW);
    }

    @Test
    void anApprovedRegistrationCannotBeRejected() {
        when(registrations.findById(9L)).thenReturn(Optional.of(registration(RegistrationStatus.APPROVED)));
        lenient().when(userService.getUser(ORGANIZER.id())).thenReturn(user(ORGANIZER.id()));

        assertThatThrownBy(() -> service.reject(ORGANIZER, 9L, "too late"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«aprobada»");
    }

    @Test
    void anApprovedRegistrationCannotBeApprovedTwice() {
        when(registrations.findById(9L)).thenReturn(Optional.of(registration(RegistrationStatus.APPROVED)));
        givenLockedTournament(TournamentStatus.ACTIVE);
        givenApprovedCount(1L);
        lenient().when(userService.getUser(ORGANIZER.id())).thenReturn(user(ORGANIZER.id()));

        assertThatThrownBy(() -> service.approve(ORGANIZER, 9L, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«aprobada»");
    }

    // --- cancel -------------------------------------------------------------------------------

    @Test
    void theCaptainCancelsARegistrationUnderReview() {
        Registration registration = registration(RegistrationStatus.UNDER_REVIEW);
        when(registrations.findById(9L)).thenReturn(Optional.of(registration));

        var response = service.cancel(CAPTAIN, 9L);

        assertThat(response.status()).isEqualTo(RegistrationStatus.CANCELLED);
    }

    @Test
    void anApprovedRegistrationCannotBeCancelled() {
        when(registrations.findById(9L)).thenReturn(Optional.of(registration(RegistrationStatus.APPROVED)));

        assertThatThrownBy(() -> service.cancel(CAPTAIN, 9L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("«aprobada»");
    }

    @Test
    void anotherMemberCannotCancelTheRegistration() {
        when(registrations.findById(9L)).thenReturn(Optional.of(registration(RegistrationStatus.UNDER_REVIEW)));

        assertThatThrownBy(() -> service.cancel(OTHER_MEMBER, 9L))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("capitán");
    }

    // --- helpers ------------------------------------------------------------------------------

    private Tournament givenTournament(TournamentStatus status, LocalDate deadline, int maxTeams) {
        Tournament tournament = tournament(status, deadline, maxTeams);
        when(tournamentService.requireTournament(1L)).thenReturn(tournament);
        return tournament;
    }

    private void givenLockedTournament(TournamentStatus status) {
        when(tournamentService.requireTournamentForUpdate(1L))
                .thenReturn(tournament(status, TODAY.plusDays(5), 8));
    }

    private void givenTeamOfCaptain() {
        when(teamService.findActiveTeamOf(CAPTAIN.id())).thenReturn(Optional.of(team()));
    }

    private void givenNoLiveRegistration() {
        when(registrations.findFirstByTournamentIdAndTeamIdAndStatusInOrderByIdDesc(eq(1L), eq(5L), anyCollection()))
                .thenReturn(Optional.empty());
    }

    private void givenApprovedCount(long count) {
        when(registrations.countByTournamentIdAndStatus(1L, RegistrationStatus.APPROVED)).thenReturn(count);
    }

    private void givenEligible(boolean eligible) {
        when(teamService.eligibility(5L)).thenReturn(new TeamEligibility.Result(eligible,
                eligible ? List.of() : List.of("El equipo tiene 4 integrantes; se requieren al menos 7.")));
    }

    private static Tournament tournament(TournamentStatus status, LocalDate deadline, int maxTeams) {
        return Tournament.builder()
                .id(1L)
                .name("TechCup 2026-1")
                .startDate(TODAY.plusDays(10))
                .endDate(TODAY.plusDays(40))
                .registrationDeadline(deadline)
                .maxTeams(maxTeams)
                .fee(BigDecimal.TEN)
                .status(status)
                .build();
    }

    private static Team team() {
        AppUser captain = user(CAPTAIN.id());
        Team team = Team.builder().id(5L).name("Tigers").colors("orange").captain(captain)
                .status(TeamStatus.ACTIVE).build();
        team.addMember(captain);
        return team;
    }

    private static Registration registration(RegistrationStatus status) {
        return Registration.builder()
                .id(9L)
                .tournament(tournament(TournamentStatus.ACTIVE, TODAY.plusDays(5), 8))
                .team(team())
                .receiptFileId("receipt-file")
                .status(status)
                .createdAt(Instant.now())
                .build();
    }

    private static AppUser user(Long id) {
        return AppUser.builder().id(id).fullName("User " + id).build();
    }
}

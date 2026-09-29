package edu.escuelaing.techcup.shared.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.application.MatchService;
import edu.escuelaing.techcup.competition.application.StandingsService;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.api.dto.TeamResponse;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.tournaments.api.dto.RegistrationResponse;
import edu.escuelaing.techcup.tournaments.api.dto.TournamentResponse;
import edu.escuelaing.techcup.tournaments.application.RegistrationService;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.RegistrationStatus;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The receipt reference on the home page is for the captain only. */
@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    private static final AuthenticatedUser CAPTAIN = new AuthenticatedUser(20L, "c@escuelaing.edu.co", Set.of("PLAYER", "CAPTAIN"));
    private static final AuthenticatedUser MEMBER = new AuthenticatedUser(21L, "m@escuelaing.edu.co", Set.of("PLAYER"));

    @Mock
    private TournamentService tournamentService;
    @Mock
    private RegistrationService registrationService;
    @Mock
    private TeamService teamService;
    @Mock
    private MatchService matchService;
    @Mock
    private StandingsService standingsService;
    @InjectMocks
    private HomeService homeService;

    @BeforeEach
    void setUp() {
        TeamResponse team = new TeamResponse(5L, "Tigers", "orange", TeamStatus.ACTIVE,
                new TeamResponse.Captain(20L, "Captain"), List.of(), 7, false);
        TournamentResponse tournament = new TournamentResponse(1L, "TechCup", LocalDate.of(2026, 3, 10),
                LocalDate.of(2026, 4, 10), LocalDate.of(2026, 3, 5), 8, BigDecimal.TEN, TournamentStatus.ACTIVE,
                "rulebook", List.of(), 3);
        RegistrationResponse registration = new RegistrationResponse(9L, 1L, 5L, "Tigers", "receipt-file",
                RegistrationStatus.UNDER_REVIEW, null, Instant.now(), null);
        when(teamService.findMine(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.of(team));
        when(tournamentService.findCurrent()).thenReturn(Optional.of(tournament));
        when(registrationService.findByTeam(1L, 5L)).thenReturn(Optional.of(registration));
    }

    @Test
    void theCaptainGetsTheReceiptReference() {
        HomeResponse home = homeService.home(CAPTAIN);

        assertThat(home.myRegistration().receiptFileId()).isEqualTo("receipt-file");
    }

    @Test
    void otherMembersGetTheRegistrationWithoutTheReceipt() {
        HomeResponse home = homeService.home(MEMBER);

        assertThat(home.myRegistration()).isNotNull();
        assertThat(home.myRegistration().status()).isEqualTo(RegistrationStatus.UNDER_REVIEW);
        assertThat(home.myRegistration().receiptFileId()).isNull();
    }
}

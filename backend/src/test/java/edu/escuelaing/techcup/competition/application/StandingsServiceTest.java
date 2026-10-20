package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.domain.GroupTeam;
import edu.escuelaing.techcup.competition.domain.Match;
import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.competition.domain.TournamentGroup;
import edu.escuelaing.techcup.competition.infrastructure.GroupTeamRepository;
import edu.escuelaing.techcup.competition.infrastructure.MatchRepository;
import edu.escuelaing.techcup.competition.infrastructure.TournamentGroupRepository;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.teams.application.TeamService;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.tournaments.application.TournamentService;
import edu.escuelaing.techcup.tournaments.domain.Tournament;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The general table (unchanged, legacy matches included) and one table per group. */
@ExtendWith(MockitoExtension.class)
class StandingsServiceTest {

    @Mock
    private MatchRepository matches;
    @Mock
    private TournamentGroupRepository groups;
    @Mock
    private GroupTeamRepository groupTeams;
    @Mock
    private TournamentService tournamentService;
    @Mock
    private TeamService teamService;

    private final Tournament tournament = Tournament.builder().id(1L).name("TechCup").build();
    private final TournamentGroup groupA = TournamentGroup.builder().id(5L).name("Grupo A").position(0).build();
    private final TournamentGroup groupB = TournamentGroup.builder().id(6L).name("Grupo B").position(1).build();
    private final Team tigers = team(10L, "Tigers");
    private final Team lions = team(20L, "Lions");
    private final Team pumas = team(30L, "Pumas");
    private final Team bears = team(40L, "Bears");
    private final Team wolves = team(50L, "Wolves");

    private StandingsService service;

    @BeforeEach
    void setUp() {
        service = new StandingsService(matches, groups, groupTeams, tournamentService, teamService);
        lenient().when(groups.findByTournamentIdOrderByPositionAsc(1L)).thenReturn(List.of(groupA, groupB));
        lenient().when(groupTeams.findByTournament(1L)).thenReturn(List.of(
                assignment(groupA, lions), assignment(groupA, tigers), assignment(groupA, wolves),
                assignment(groupB, bears), assignment(groupB, pumas)));
        lenient().when(matches.findByTournamentIdAndStatusOrderByScheduledAtAscIdAsc(1L, MatchStatus.PLAYED))
                .thenReturn(List.of(
                        played(groupA, tigers, lions, 2, 0),
                        played(groupA, wolves, tigers, 1, 1),
                        played(groupB, pumas, bears, 0, 3),
                        played(null, lions, pumas, 4, 0),
                        knockout(tigers, bears, 5, 0)));
    }

    @Test
    void eachGroupTableCountsOnlyTheMatchesOfThatGroup() {
        var tables = service.groupTables(1L);

        assertThat(tables).extracting(StandingsService.GroupTable::name).containsExactly("Grupo A", "Grupo B");
        var groupATable = tables.get(0).rows();
        assertThat(groupATable).extracting(row -> row.teamId()).containsExactly(10L, 50L, 20L);
        assertThat(groupATable.get(0).points()).isEqualTo(4);
        assertThat(groupATable.get(0).played()).isEqualTo(2);
        // Lions: the 4-0 legacy match without a group does not count here.
        assertThat(groupATable.get(2).played()).isEqualTo(1);
        assertThat(groupATable.get(2).points()).isZero();
        // Teams may have played a different number of matches.
        assertThat(groupATable.get(1).played()).isEqualTo(1);

        var groupBTable = tables.get(1).rows();
        assertThat(groupBTable).extracting(row -> row.teamId()).containsExactly(40L, 30L);
        assertThat(groupBTable.get(0).goalsFor()).isEqualTo(3);
    }

    @Test
    void theGeneralTableKeepsCountingEveryPlayedGroupMatch() {
        when(tournamentService.approvedTeamIds(1L)).thenReturn(List.of(10L, 20L, 30L, 40L, 50L));
        for (Team team : List.of(tigers, lions, pumas, bears, wolves)) {
            when(teamService.requireTeam(team.getId())).thenReturn(team);
        }

        var rows = service.rows(1L);

        // Lions: 0-2 against Tigers plus the 4-0 legacy group match; the knockout 5-0 never counts.
        var lionsRow = rows.stream().filter(row -> row.teamId().equals(20L)).findFirst().orElseThrow();
        assertThat(lionsRow.played()).isEqualTo(2);
        assertThat(lionsRow.points()).isEqualTo(3);
        var tigersRow = rows.stream().filter(row -> row.teamId().equals(10L)).findFirst().orElseThrow();
        assertThat(tigersRow.played()).isEqualTo(2);
        assertThat(tigersRow.goalsFor()).isEqualTo(3);
    }

    @Test
    void aTournamentWithoutGroupsHasNoGroupTables() {
        when(groups.findByTournamentIdOrderByPositionAsc(1L)).thenReturn(List.of());

        assertThat(service.groupTables(1L)).isEmpty();
    }

    @Test
    void theGroupTablesOfASuspendedTournamentAreHiddenFromThePublic() {
        when(tournamentService.requireVisible(1L, null)).thenThrow(NotFoundException.of("el torneo", 1L));

        assertThatThrownBy(() -> service.groupStandings(1L, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.standings(1L, null)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(matches);
    }

    @Test
    void groupStandingsAreExposedPerGroup() {
        var response = service.groupStandings(1L, null);

        assertThat(response).extracting(table -> table.groupId()).containsExactly(5L, 6L);
        assertThat(response.get(0).rows()).extracting(row -> row.position()).containsExactly(1, 2, 3);
    }

    private Match played(TournamentGroup group, Team home, Team away, int homeScore, int awayScore) {
        return Match.builder().tournament(tournament).phase(MatchPhase.GROUP).group(group).roundNumber(1)
                .homeTeam(home).awayTeam(away).scheduledAt(Instant.parse("2026-03-10T18:00:00Z"))
                .status(MatchStatus.PLAYED).homeScore(homeScore).awayScore(awayScore).build();
    }

    private Match knockout(Team home, Team away, int homeScore, int awayScore) {
        return Match.builder().tournament(tournament).phase(MatchPhase.SEMIFINAL).roundNumber(4)
                .homeTeam(home).awayTeam(away).scheduledAt(Instant.parse("2026-03-20T18:00:00Z"))
                .status(MatchStatus.PLAYED).homeScore(homeScore).awayScore(awayScore).build();
    }

    private static GroupTeam assignment(TournamentGroup group, Team team) {
        return GroupTeam.builder().group(group).tournamentId(1L).team(team).registrationId(team.getId()).build();
    }

    private static Team team(Long id, String name) {
        return Team.builder().id(id).name(name).colors("blue")
                .captain(AppUser.builder().id(id * 100).fullName("Captain " + id).build())
                .status(TeamStatus.ACTIVE).build();
    }
}

package edu.escuelaing.techcup.teams.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.teams.application.MemberProfilePort;
import edu.escuelaing.techcup.teams.application.MemberProfilePort.MemberProfile;
import edu.escuelaing.techcup.teams.application.TeamLockPort;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A member's academic program change while the team is approved in a tournament that has not started. */
@ExtendWith(MockitoExtension.class)
class TeamEligibilityGuardAdapterTest {

    @Mock
    private TeamRepository teams;
    @Mock
    private TeamLockPort teamLock;
    @Mock
    private MemberProfilePort memberProfiles;
    @InjectMocks
    private TeamEligibilityGuardAdapter guard;

    @Test
    void movingIntoAnOrganizingProgramNeverNeedsTheTeam() {
        guard.requireProgramChangeKeepsTeamEligible(101L, AcademicProgram.AI_ENGINEERING);

        verify(teams, never()).findActiveTeamByMember(anyLong());
    }

    @Test
    void aTeamThatIsNotApprovedInAnUpcomingTournamentIsNotChecked() {
        Team team = teamOf(8, 5);
        givenTeam(team);

        assertThatCode(() -> guard.requireProgramChangeKeepsTeamEligible(101L, AcademicProgram.OTHER))
                .doesNotThrowAnyException();
        verify(memberProfiles, never()).findProfiles(anyCollection());
    }

    @Test
    void aChangeThatBreaksTheProgramMajorityOfAnApprovedTeamIsRefused() {
        // 9 members, 5 organizing (5 * 2 > 9); member 101 moving to OTHER leaves 4 of 9.
        Team team = teamOf(9, 5);
        givenTeam(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));
        givenProfiles();

        assertThatThrownBy(() -> guard.requireProgramChangeKeepsTeamEligible(101L, AcademicProgram.OTHER))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El equipo 'Tigers' está inscrito en el torneo «Copa», que aún no inicia: debe seguir "
                        + "cumpliendo los requisitos para participar y con este cambio no los cumpliría. "
                        + "Más de la mitad de los integrantes deben pertenecer a los programas de Sistemas, "
                        + "Inteligencia Artificial, Ciberseguridad o Estadística (4 de 9 cumplen).");
        // The team row is locked before the registration state is read.
        InOrder order = inOrder(teams, teamLock);
        order.verify(teams).findByIdForUpdate(5L);
        order.verify(teamLock).upcomingTournamentsOf(5L);
    }

    @Test
    void aChangeThatKeepsTheMajorityGoesThrough() {
        Team team = teamOf(9, 7);
        givenTeam(team);
        when(teamLock.upcomingTournamentsOf(5L)).thenReturn(List.of("Copa"));
        givenProfiles();

        assertThatCode(() -> guard.requireProgramChangeKeepsTeamEligible(101L, null)).doesNotThrowAnyException();
    }

    @Test
    void aPlayerWithoutTeamIsNotChecked() {
        when(teams.findActiveTeamByMember(101L)).thenReturn(Optional.empty());

        guard.requireProgramChangeKeepsTeamEligible(101L, AcademicProgram.OTHER);

        verify(teams, never()).findByIdForUpdate(any());
    }

    /** Captain 20 plus members 101.., the first {@code organizing} of them (captain included) from Systems. */
    private static Team teamOf(int size, int organizing) {
        AppUser captain = user(20L, AcademicProgram.SYSTEMS_ENGINEERING);
        Team team = Team.builder().id(5L).name("Tigers").colors("orange").captain(captain)
                .status(TeamStatus.ACTIVE).build();
        team.addMember(captain);
        for (long id = 101; team.memberCount() < size; id++) {
            boolean fromOrganizing = team.memberCount() < organizing;
            team.addMember(user(id, fromOrganizing ? AcademicProgram.SYSTEMS_ENGINEERING : AcademicProgram.OTHER));
        }
        return team;
    }

    private void givenTeam(Team team) {
        when(teams.findActiveTeamByMember(101L)).thenReturn(Optional.of(team));
        when(teams.findByIdForUpdate(5L)).thenReturn(Optional.of(team));
    }

    private void givenProfiles() {
        when(memberProfiles.findProfiles(anyCollection())).thenAnswer(inv -> {
            Map<Long, MemberProfile> profiles = new HashMap<>();
            for (Object id : (Collection<?>) inv.getArgument(0)) {
                profiles.put((Long) id, new MemberProfile((Long) id, Position.MIDFIELDER, ((Long) id).intValue(), null));
            }
            return profiles;
        });
    }

    private static AppUser user(Long id, AcademicProgram program) {
        return AppUser.builder().id(id).fullName("User " + id).academicProgram(program).build();
    }
}

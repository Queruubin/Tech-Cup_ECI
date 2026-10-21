package edu.escuelaing.techcup.teams.domain;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.teams.domain.TeamEligibility.MemberFacts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamEligibilityTest {

    @Test
    void eightValidMembersAreEligible() {
        var result = TeamEligibility.check(members(8, AcademicProgram.SYSTEMS_ENGINEERING));

        assertThat(result.eligible()).isTrue();
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void thirteenValidMembersAreEligible() {
        assertThat(TeamEligibility.check(members(13, AcademicProgram.AI_ENGINEERING)).eligible()).isTrue();
    }

    @Test
    void sevenMembersAreTooFew() {
        var result = TeamEligibility.check(members(7, AcademicProgram.SYSTEMS_ENGINEERING));

        assertThat(result.eligible()).isFalse();
        assertThat(result.problems()).containsExactly("El equipo tiene 7 integrantes; se requieren al menos 8.");
    }

    @Test
    void moreThanThirteenMembersIsAProblem() {
        var result = TeamEligibility.check(members(14, AcademicProgram.SYSTEMS_ENGINEERING));

        assertThat(result.eligible()).isFalse();
        assertThat(result.problems()).containsExactly("El equipo tiene 14 integrantes; se permiten máximo 13.");
    }

    @Test
    void duplicateJerseyNumbersAreReported() {
        List<MemberFacts> members = members(8, AcademicProgram.SYSTEMS_ENGINEERING);
        members.set(6, new MemberFacts(6L, "Member 6", true, 1, AcademicProgram.SYSTEMS_ENGINEERING));

        var result = TeamEligibility.check(members);

        assertThat(result.eligible()).isFalse();
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("Números de camiseta repetidos: [1]"));
    }

    @Test
    void membersWithoutProfileAreReported() {
        List<MemberFacts> members = members(8, AcademicProgram.SYSTEMS_ENGINEERING);
        members.set(0, new MemberFacts(0L, "Member 0", false, null, AcademicProgram.SYSTEMS_ENGINEERING));

        var result = TeamEligibility.check(members);

        assertThat(result.eligible()).isFalse();
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("sin perfil deportivo: Member 0"));
    }

    @Test
    void strictlyMoreThanHalfMustBeFromOrganizingPrograms() {
        // 8 members, exactly half from OTHER -> not eligible
        List<MemberFacts> half = new ArrayList<>(members(4, AcademicProgram.STATISTICS_ENGINEERING));
        for (long i = 4; i < 8; i++) {
            half.add(new MemberFacts(i, "Member " + i, true, (int) i + 1, AcademicProgram.OTHER));
        }
        assertThat(TeamEligibility.check(half).eligible()).isFalse();
        assertThat(TeamEligibility.check(half).problems()).anySatisfy(p -> assertThat(p).contains("Más de la mitad"));

        // 5 of 8 from organizing programs -> eligible
        half.set(7, new MemberFacts(7L, "Member 7", true, 8, AcademicProgram.CYBERSECURITY_ENGINEERING));
        assertThat(TeamEligibility.check(half).eligible()).isTrue();
    }

    @Test
    void nullProgramCountsAsOther() {
        List<MemberFacts> members = new ArrayList<>(members(3, AcademicProgram.SYSTEMS_ENGINEERING));
        for (long i = 3; i < 7; i++) {
            members.add(new MemberFacts(i, "Member " + i, true, (int) i + 1, null));
        }

        assertThat(TeamEligibility.check(members).eligible()).isFalse();
    }

    @Test
    void reportsEveryProblemAtOnce() {
        List<MemberFacts> members = new ArrayList<>();
        members.add(new MemberFacts(1L, "A", true, 1, AcademicProgram.OTHER));
        members.add(new MemberFacts(2L, "B", false, null, AcademicProgram.OTHER));
        members.add(new MemberFacts(3L, "C", true, 1, AcademicProgram.SYSTEMS_ENGINEERING));

        var result = TeamEligibility.check(members);

        assertThat(result.problems()).hasSize(4);
    }

    private static List<MemberFacts> members(int count, AcademicProgram program) {
        List<MemberFacts> list = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            list.add(new MemberFacts(i, "Member " + i, true, (int) i + 1, program));
        }
        return list;
    }
}

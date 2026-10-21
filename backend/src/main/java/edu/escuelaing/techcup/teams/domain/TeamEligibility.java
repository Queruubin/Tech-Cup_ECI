package edu.escuelaing.techcup.teams.domain;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.shared.exception.Messages;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure domain rule (spec 7.3 "Validaciones") used when a team registers for a tournament:
 * <ul>
 *   <li>between {@value #MIN_MEMBERS} and {@value #MAX_MEMBERS} members;</li>
 *   <li>no duplicate jersey numbers;</li>
 *   <li>strictly more than half of the members belong to an organizing program
 *       (Systems, AI, Cybersecurity, Statistics), i.e. program != OTHER;</li>
 *   <li>every member has a sport profile.</li>
 * </ul>
 * Returns every problem found instead of failing at the first one, so captains can fix them all.
 */
public final class TeamEligibility {

    /**
     * Roster bounds, the single source of truth for the backend (product decision of 2026-10, it
     * replaces the 7..12 of the original specification). The minimum gates the tournament
     * registration (and its approval); the maximum also caps every new membership
     * ({@code TeamService.addMember}), so a team never grows past it.
     */
    public static final int MIN_MEMBERS = 8;
    public static final int MAX_MEMBERS = 13;

    /** What the rule needs to know about one member. {@code jerseyNumber} is null without a profile. */
    public record MemberFacts(Long userId, String fullName, boolean hasProfile, Integer jerseyNumber,
                              AcademicProgram academicProgram) {
    }

    public record Result(boolean eligible, List<String> problems) {
    }

    private TeamEligibility() {
    }

    public static Result check(List<MemberFacts> members) {
        List<String> problems = new ArrayList<>();
        int size = members.size();

        if (size < MIN_MEMBERS) {
            problems.add("El equipo tiene " + Messages.plural(size, "integrante", "integrantes")
                    + "; se requieren al menos " + MIN_MEMBERS + ".");
        }
        if (size > MAX_MEMBERS) {
            problems.add("El equipo tiene " + Messages.plural(size, "integrante", "integrantes")
                    + "; se permiten máximo " + MAX_MEMBERS + ".");
        }

        List<String> withoutProfile = members.stream()
                .filter(m -> !m.hasProfile())
                .map(MemberFacts::fullName)
                .toList();
        if (!withoutProfile.isEmpty()) {
            problems.add("Integrantes sin perfil deportivo: " + String.join(", ", withoutProfile) + ".");
        }

        Set<Integer> seen = new HashSet<>();
        Set<Integer> duplicated = new TreeSet<>();
        for (MemberFacts member : members) {
            if (member.jerseyNumber() != null && !seen.add(member.jerseyNumber())) {
                duplicated.add(member.jerseyNumber());
            }
        }
        if (!duplicated.isEmpty()) {
            problems.add("Números de camiseta repetidos: " + duplicated + ".");
        }

        long fromOrganizingPrograms = members.stream()
                .filter(m -> m.academicProgram() != null && m.academicProgram().isOrganizingProgram())
                .count();
        if (size > 0 && fromOrganizingPrograms * 2 <= size) {
            problems.add("Más de la mitad de los integrantes deben pertenecer a los programas de Sistemas, "
                    + "Inteligencia Artificial, Ciberseguridad o Estadística ("
                    + fromOrganizingPrograms + " de " + size + " cumplen).");
        }

        return new Result(problems.isEmpty(), List.copyOf(problems));
    }
}

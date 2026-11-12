package edu.escuelaing.techcup.identity.application;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;

/**
 * Lets identity ask the teams module whether a member's academic program may change: while the
 * member's team is APPROVED in a tournament that has not started, the team must keep passing the
 * eligibility rule its approval checked (program majority included). Expressed as a port so
 * identity never depends on teams code (teams already depends on identity); implemented in
 * {@code teams.infrastructure.TeamEligibilityGuardAdapter}.
 */
public interface TeamEligibilityGuardPort {

    /**
     * Locks the user's ACTIVE team row and refuses (BusinessRuleException, 409) when that team is
     * APPROVED in a not-started tournament and would no longer be eligible with the user's
     * academic program set to {@code newProgram}. Does nothing otherwise.
     */
    void requireProgramChangeKeepsTeamEligible(Long userId, AcademicProgram newProgram);
}

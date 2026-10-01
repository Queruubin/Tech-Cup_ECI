package edu.escuelaing.techcup.teams.application;

import edu.escuelaing.techcup.identity.application.RoleService;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ConflictException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.Messages;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.teams.api.dto.CreateTeamRequest;
import edu.escuelaing.techcup.teams.api.dto.TeamResponse;
import edu.escuelaing.techcup.teams.api.dto.UpdateTeamRequest;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import edu.escuelaing.techcup.teams.domain.TeamMember;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.teams.infrastructure.TeamRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team use cases (spec 7.3). Rules enforced here:
 * <ul>
 *   <li>Create (any PLAYER): unique name; the creator needs a sport profile, becomes the first
 *       member and, in the same transaction, the team captain (CAPTAIN role granted through
 *       {@link RoleService#grantCaptainForNewTeam}); a user belongs to at most one ACTIVE team.</li>
 *   <li>Add member: team ACTIVE and not locked ({@link TeamLockPort}: its roster is frozen while
 *       it plays a tournament), fewer than {@value TeamEligibility#MAX_MEMBERS} members, player
 *       has a profile, jersey number unique within the team, player not in another active team.</li>
 *   <li>Update name/colors, remove a member, inactivate: only by the team captain (or ADMIN) and
 *       only while the team is not locked by an approved registration in an ACTIVE or
 *       IN_PROGRESS tournament ({@link TeamLockPort}). The captain cannot be removed.
 *       Inactivating a team also cancels its pending join requests and invitations
 *       ({@link TeamJoinRequestPort}) and revokes CAPTAIN from its captain
 *       ({@link RoleService#revokeCaptainForClosedTeam}).</li>
 *   <li>Eligibility (7 to 12 members, unique jerseys, program majority, profiles) is delegated
 *       to the pure domain rule {@link TeamEligibility}.</li>
 * </ul>
 * Audited as TEAM_CREATED / TEAM_UPDATED / TEAM_MEMBER_REMOVED / TEAM_INACTIVATED.
 */
@Service
public class TeamService {

    static final String ENTITY_TYPE = "TEAM";
    static final String TEAM_INACTIVATED = "TEAM_INACTIVATED";
    static final String ROSTER_FROZEN = "ROSTER_FROZEN";

    private final TeamRepository teams;
    private final UserService userService;
    private final RoleService roleService;
    private final MemberProfilePort memberProfiles;
    private final TeamLockPort teamLock;
    private final TeamJoinRequestPort joinRequests;
    private final TeamResponseAssembler assembler;
    private final AuditService auditService;

    public TeamService(TeamRepository teams, UserService userService, RoleService roleService,
                       MemberProfilePort memberProfiles, TeamLockPort teamLock, TeamJoinRequestPort joinRequests, TeamResponseAssembler assembler,
                       AuditService auditService) {
        this.teams = teams;
        this.userService = userService;
        this.roleService = roleService;
        this.memberProfiles = memberProfiles;
        this.teamLock = teamLock;
        this.joinRequests = joinRequests;
        this.assembler = assembler;
        this.auditService = auditService;
    }

    @Transactional
    public TeamResponse create(AuthenticatedUser actor, CreateTeamRequest request) {
        String name = request.name().trim();
        if (teams.existsByNameIgnoreCase(name)) {
            throw new ConflictException("Ya existe un equipo llamado '" + name + "'.");
        }
        AppUser captain = userService.getUser(actor.id());
        if (memberProfiles.findProfile(captain.getId()).isEmpty()) {
            throw new BusinessRuleException("Cree su perfil deportivo antes de crear un equipo: el capitán también es jugador.");
        }
        teams.findActiveTeamByMember(captain.getId()).ifPresent(existing -> {
            throw new BusinessRuleException("Usted ya pertenece al equipo '" + existing.getName() + "'.");
        });

        Team team = Team.builder()
                .name(name)
                .colors(request.colors().trim())
                .captain(captain)
                .status(TeamStatus.ACTIVE)
                .build();
        team.addMember(captain);
        team = teams.save(team);
        roleService.grantCaptainForNewTeam(captain.getId(), team.getId());

        auditService.record(actor.id(), AuditAction.TEAM_CREATED, ENTITY_TYPE, team.getId(),
                Map.of("name", team.getName(), "colors", team.getColors()));
        return assembler.toResponse(team);
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> list() {
        return teams.findAllByOrderByNameAsc().stream().map(assembler::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<TeamResponse> findMine(Long userId) {
        return teams.findActiveTeamByMember(userId).map(assembler::toResponse);
    }

    @Transactional(readOnly = true)
    public TeamResponse get(Long teamId) {
        return assembler.toResponse(requireTeam(teamId));
    }

    @Transactional
    public TeamResponse update(AuthenticatedUser actor, Long teamId, UpdateTeamRequest request) {
        Team team = requireTeam(teamId);
        requireCaptainOrAdmin(actor, team);
        requireUnlocked(team);

        Map<String, Object> changes = new HashMap<>();
        if (request.name() != null && !request.name().isBlank()) {
            String name = request.name().trim();
            teams.findByNameIgnoreCase(name)
                    .filter(other -> !other.getId().equals(teamId))
                    .ifPresent(other -> {
                        throw new ConflictException("Ya existe un equipo llamado '" + name + "'.");
                    });
            team.setName(name);
            changes.put("name", name);
        }
        if (request.colors() != null && !request.colors().isBlank()) {
            team.setColors(request.colors().trim());
            changes.put("colors", team.getColors());
        }
        if (changes.isEmpty()) {
            throw new BusinessRuleException("No hay nada que actualizar: indique un nombre o unos colores.");
        }
        auditService.record(actor.id(), AuditAction.TEAM_UPDATED, ENTITY_TYPE, teamId, changes);
        return assembler.toResponse(team);
    }

    @Transactional
    public TeamResponse removeMember(AuthenticatedUser actor, Long teamId, Long userId) {
        Team team = requireTeam(teamId);
        requireCaptainOrAdmin(actor, team);
        requireUnlocked(team);
        if (team.isCaptain(userId)) {
            throw new BusinessRuleException("No se puede retirar al capitán del equipo.");
        }
        team.removeMember(userId);
        auditService.record(actor.id(), AuditAction.TEAM_MEMBER_REMOVED, ENTITY_TYPE, teamId,
                Map.of("userId", userId));
        return assembler.toResponse(team);
    }

    /**
     * Inactivates the team, closes the join requests and invitations still waiting for it and
     * takes the CAPTAIN role away from its captain.
     */
    @Transactional
    public TeamResponse inactivate(AuthenticatedUser actor, Long teamId) {
        Team team = requireTeam(teamId);
        requireCaptainOrAdmin(actor, team);
        requireUnlocked(team);
        if (!team.isActive()) {
            throw new BusinessRuleException("El equipo ya está inactivo.");
        }
        team.setStatus(TeamStatus.INACTIVE);
        int cancelled = joinRequests.cancelPendingRequestsOf(actor.id(), teamId, TEAM_INACTIVATED);
        roleService.revokeCaptainForClosedTeam(actor.id(), team.getCaptain().getId(), teamId);
        auditService.record(actor.id(), AuditAction.TEAM_INACTIVATED, ENTITY_TYPE, teamId,
                Map.of("cancelledJoinRequests", cancelled));
        return assembler.toResponse(team);
    }

    @Transactional(readOnly = true)
    public TeamEligibility.Result eligibility(Long teamId) {
        Team team = requireTeam(teamId);
        Map<Long, MemberProfilePort.MemberProfile> profiles = memberProfiles.findProfiles(team.memberIds());
        List<TeamEligibility.MemberFacts> facts = team.getMembers().stream()
                .map(TeamMember::getUser)
                .map(user -> {
                    var profile = profiles.get(user.getId());
                    return new TeamEligibility.MemberFacts(user.getId(), user.getFullName(), profile != null,
                            profile == null ? null : profile.jerseyNumber(), user.getAcademicProgram());
                })
                .toList();
        return TeamEligibility.check(facts);
    }

    /**
     * Adds a player to a team enforcing the membership rules. Called by the join-request flow of
     * the players module through {@code TeamGateway}, which locks the player's row first so the
     * "not in another team" check cannot be raced by a second acceptance.
     */
    @Transactional
    public void addMember(Long teamId, Long userId) {
        Team team = requireTeam(teamId);
        if (!team.isActive()) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' no está activo.");
        }
        if (teamLock.isLocked(teamId)) {
            throw new BusinessRuleException(Messages.frozenRoster(team.getName()));
        }
        if (team.hasMember(userId)) {
            throw new BusinessRuleException("El jugador ya es integrante del equipo '" + team.getName() + "'.");
        }
        if (team.memberCount() >= TeamEligibility.MAX_MEMBERS) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' ya está completo ("
                    + Messages.plural(TeamEligibility.MAX_MEMBERS, "integrante", "integrantes") + ").");
        }
        MemberProfilePort.MemberProfile profile = memberProfiles.findProfile(userId)
                .orElseThrow(() -> new BusinessRuleException("El jugador no tiene perfil deportivo."));
        teams.findActiveTeamByMember(userId).ifPresent(other -> {
            throw new BusinessRuleException("El jugador ya pertenece al equipo '" + other.getName() + "'.");
        });
        boolean jerseyTaken = memberProfiles.findProfiles(team.memberIds()).values().stream()
                .anyMatch(member -> member.jerseyNumber() == profile.jerseyNumber());
        if (jerseyTaken) {
            throw new BusinessRuleException("El número de camiseta " + profile.jerseyNumber()
                    + " ya está en uso en el equipo '" + team.getName() + "'.");
        }
        team.addMember(userService.getUser(userId));
    }

    /**
     * Closes recruitment when the team's roster becomes frozen (its tournament registration was
     * approved): the join requests and invitations still pending could never be accepted any more,
     * so they are cancelled instead of being left for players to trip over.
     *
     * @return how many requests and invitations were cancelled
     */
    @Transactional
    public int closeRecruitment(Long actorUserId, Long teamId) {
        return joinRequests.cancelPendingRequestsOf(actorUserId, teamId, ROSTER_FROZEN);
    }

    /** Whether the team is registered (APPROVED) in an ACTIVE or IN_PROGRESS tournament. */
    @Transactional(readOnly = true)
    public boolean isLocked(Long teamId) {
        return teamLock.isLocked(teamId);
    }

    @Transactional(readOnly = true)
    public Optional<Team> findActiveTeamOf(Long userId) {
        return teams.findActiveTeamByMember(userId);
    }

    @Transactional(readOnly = true)
    public Team requireTeam(Long teamId) {
        return teams.findById(teamId).orElseThrow(() -> NotFoundException.of("el equipo", teamId));
    }

    private static void requireCaptainOrAdmin(AuthenticatedUser actor, Team team) {
        if (!actor.isAdmin() && !team.isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '" + team.getName() + "' puede realizar esta acción.");
        }
    }

    private void requireUnlocked(Team team) {
        if (teamLock.isLocked(team.getId())) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' está inscrito en un torneo activo "
                    + "o en progreso, por lo que no se puede modificar.");
        }
    }
}

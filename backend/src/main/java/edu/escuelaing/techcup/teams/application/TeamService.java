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
import edu.escuelaing.techcup.shared.security.Roles;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.shared.storage.FileUpload;
import edu.escuelaing.techcup.teams.api.dto.CreateTeamRequest;
import edu.escuelaing.techcup.teams.api.dto.TeamResponse;
import edu.escuelaing.techcup.teams.api.dto.UpdateTeamRequest;
import edu.escuelaing.techcup.teams.domain.Team;
import edu.escuelaing.techcup.teams.domain.TeamEligibility;
import edu.escuelaing.techcup.teams.domain.TeamMember;
import edu.escuelaing.techcup.teams.domain.TeamStatus;
import edu.escuelaing.techcup.teams.infrastructure.TeamRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Team use cases (spec 7.3). Rules enforced here:
 * <ul>
 *   <li>Create (any PLAYER): unique name; the creator needs a sport profile, becomes the first
 *       member and, in the same transaction, the team captain (CAPTAIN role granted through
 *       {@link RoleService#grantCaptainForNewTeam}); a user belongs to at most one ACTIVE team.</li>
 *   <li>Roster freeze ({@link TeamLockPort#isLocked}): a team with an APPROVED registration in a
 *       tournament that has started (IN_PROGRESS, or SUSPENDED from IN_PROGRESS) is locked until
 *       the tournament finishes: no member joins, leaves or is removed, and the team cannot be
 *       renamed or inactivated. Paying and being approved does not freeze it.</li>
 *   <li>Registered but not started ({@link TeamLockPort#upcomingTournamentsOf}): while the team is
 *       APPROVED in a tournament that is ACTIVE (or SUSPENDED from ACTIVE), every roster change
 *       (add, remove, leave) must leave a roster that still passes {@link TeamEligibility}, the
 *       rule the approval checked ({@value TeamEligibility#MIN_MEMBERS} to
 *       {@value TeamEligibility#MAX_MEMBERS} members, program majority...), so an approved team
 *       stays valid without a second approval and is valid whenever its tournament starts.</li>
 *   <li>Add member: team ACTIVE and not locked, fewer than {@value TeamEligibility#MAX_MEMBERS}
 *       members, player has a profile, jersey number unique within the team, player not in
 *       another active team, plus the registered-team rule above.</li>
 *   <li>Update name/colors, remove a member, inactivate: only by the team captain (or ADMIN) and
 *       only while the team is not locked. The captain cannot be removed. Renaming is allowed
 *       before the tournament starts (it is cosmetic); inactivating is refused while the team is
 *       APPROVED in a not-started tournament too (an organizer removes it from the tournament
 *       first). Inactivating a team also cancels its pending join requests and invitations
 *       ({@link TeamJoinRequestPort}) and revokes CAPTAIN from its captain
 *       ({@link RoleService#revokeCaptainForClosedTeam}).</li>
 *   <li>Row locks: every roster change (add, remove, leave) and the inactivation lock the team row
 *       first and only then read the registration state, so they serialise with the registration
 *       approval (tournament row, then team row; it re-runs the eligibility rule) and with the
 *       tournament start, which locks every approved team's row to close its recruitment
 *       ({@link #closeRecruitment}). A change either commits before the approval or the start (the
 *       approval re-checks the result, the start finds a valid roster) or runs after it and sees
 *       the new state (the registered-team rule, or the freeze).</li>
 *   <li>Delete (ADMIN only): refused while the team plays any match ({@link TeamMatchPort}) or has
 *       a registration UNDER_REVIEW or APPROVED ({@link TeamRegistrationPort}); the admin deletes
 *       (or suspends, then deletes) that tournament first. Otherwise the team goes with its
 *       memberships and join requests/invitations ({@code ON DELETE CASCADE}) and its REJECTED or
 *       CANCELLED registrations, whose receipts and university-form evidence are deleted after
 *       commit, as is the team photo; the players stay as users. Its captain loses CAPTAIN unless
 *       they still captain an ACTIVE team.</li>
 *   <li>Team photo (optional, one per team): replaced or removed by the team captain, an ORGANIZER
 *       or an ADMIN at any time. Deliberately <b>not</b> blocked by the tournament roster freeze
 *       ({@link TeamLockPort}) nor by a suspended tournament: it is cosmetic and decides nothing.
 *       Stored as an image ({@link FileKind#IMAGE}, magic bytes checked) with owner
 *       {@link FileOwner.Category#TEAM_PHOTO}; the replaced or removed binary is deleted after commit.</li>
 *   <li>Leave (any member but the captain): refused while the team is locked, like removing a
 *       member; the captain cannot leave (an admin may delete the team instead).</li>
 *   <li>Eligibility ({@value TeamEligibility#MIN_MEMBERS} to {@value TeamEligibility#MAX_MEMBERS}
 *       members, unique jerseys, program majority, profiles) is delegated to the pure domain rule
 *       {@link TeamEligibility}.</li>
 * </ul>
 * Audited as TEAM_CREATED / TEAM_UPDATED / TEAM_MEMBER_REMOVED / TEAM_MEMBER_LEFT / TEAM_INACTIVATED /
 * TEAM_DELETED / TEAM_PHOTO_UPDATED / TEAM_PHOTO_REMOVED.
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
    private final TeamMatchPort teamMatches;
    private final TeamRegistrationPort teamRegistrations;
    private final FileStorage fileStorage;
    private final FileDeletionScheduler fileDeletion;
    private final TeamResponseAssembler assembler;
    private final AuditService auditService;

    public TeamService(TeamRepository teams, UserService userService, RoleService roleService,
                       MemberProfilePort memberProfiles, TeamLockPort teamLock, TeamJoinRequestPort joinRequests,
                       TeamMatchPort teamMatches, TeamRegistrationPort teamRegistrations,
                       FileStorage fileStorage, FileDeletionScheduler fileDeletion, TeamResponseAssembler assembler,
                       AuditService auditService) {
        this.teams = teams;
        this.userService = userService;
        this.roleService = roleService;
        this.memberProfiles = memberProfiles;
        this.teamLock = teamLock;
        this.joinRequests = joinRequests;
        this.teamMatches = teamMatches;
        this.teamRegistrations = teamRegistrations;
        this.fileStorage = fileStorage;
        this.fileDeletion = fileDeletion;
        this.assembler = assembler;
        this.auditService = auditService;
    }

    @Transactional
    public TeamResponse create(AuthenticatedUser actor, CreateTeamRequest request) {
        // The ADMIN role implies PLAYER for endpoint security, but founding a team makes the caller
        // its first member and captain: only somebody who actually holds PLAYER can be that.
        if (!actor.hasRole(Roles.PLAYER)) {
            throw new ForbiddenOperationException("Solo un usuario con el rol de jugador puede crear un equipo.");
        }
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
        Team team = requireTeamForUpdate(teamId);
        requireCaptainOrAdmin(actor, team);
        requireUnlocked(team);
        if (team.isCaptain(userId)) {
            throw new BusinessRuleException("No se puede retirar al capitán del equipo.");
        }
        if (!team.hasMember(userId)) {
            throw new NotFoundException("El usuario " + userId + " no es integrante del equipo " + teamId + ".");
        }
        requireStaysEligibleIfRegistered(team, membersAfterRemoving(team, userId));
        team.removeMember(userId);
        auditService.record(actor.id(), AuditAction.TEAM_MEMBER_REMOVED, ENTITY_TYPE, teamId,
                Map.of("userId", userId));
        return assembler.toResponse(team);
    }

    /**
     * The signed-in player leaves the team. Only a member may, never its captain (a team always
     * keeps its captain, who cannot stop being one by hand), and not while the roster is frozen by
     * a started tournament ({@link TeamLockPort}); before the start, an approved team must stay
     * eligible without the leaver (the same rules as removing a member). The team row is locked
     * first, so the departure serialises with the other roster changes, with the registration
     * approval (which re-checks the eligibility under that same lock) and with the tournament start.
     *
     * <p>Only the membership ties a player to a team: the profile's and the session's team are
     * derived from it, and a member holds no pending join request or invitation (joining a team
     * cancels them all, and none can be created while in a team), so nothing else is cleared.
     */
    @Transactional
    public void leave(AuthenticatedUser actor, Long teamId) {
        Team team = requireTeamForUpdate(teamId);
        if (!team.hasMember(actor.id())) {
            throw new ForbiddenOperationException("Usted no es integrante del equipo '" + team.getName() + "'.");
        }
        if (team.isCaptain(actor.id())) {
            throw new BusinessRuleException("El capitán no puede salir del equipo. Si necesita retirarse, "
                    + "un administrador puede eliminar el equipo.");
        }
        if (teamLock.isLocked(teamId)) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' está jugando un torneo que ya "
                    + "inició; no puede salir de él hasta que el torneo finalice.");
        }
        requireStaysEligibleIfRegistered(team, membersAfterRemoving(team, actor.id()));
        team.removeMember(actor.id());
        auditService.record(actor.id(), AuditAction.TEAM_MEMBER_LEFT, ENTITY_TYPE, teamId,
                Map.of("userId", actor.id(), "remainingMembers", team.memberCount()));
    }

    /**
     * Inactivates the team, closes the join requests and invitations still waiting for it and
     * takes the CAPTAIN role away from its captain. Refused while the team plays a tournament and
     * also while it is APPROVED in one that has not started: an organizer must remove it from that
     * tournament first. The team row is locked first, so an approval cannot slip in between the
     * check and the change.
     */
    @Transactional
    public TeamResponse inactivate(AuthenticatedUser actor, Long teamId) {
        Team team = requireTeamForUpdate(teamId);
        requireCaptainOrAdmin(actor, team);
        requireUnlocked(team);
        if (!team.isActive()) {
            throw new BusinessRuleException("El equipo ya está inactivo.");
        }
        List<String> upcoming = teamLock.upcomingTournamentsOf(teamId);
        if (!upcoming.isEmpty()) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' está inscrito en "
                    + tournamentList(upcoming) + ", que aún no " + Messages.agree(upcoming.size(), "inicia", "inician")
                    + ". Un organizador debe retirarlo del torneo antes de inactivarlo.");
        }
        team.setStatus(TeamStatus.INACTIVE);
        int cancelled = joinRequests.cancelPendingRequestsOf(actor.id(), teamId, TEAM_INACTIVATED);
        roleService.revokeCaptainForClosedTeam(actor.id(), team.getCaptain().getId(), teamId);
        auditService.record(actor.id(), AuditAction.TEAM_INACTIVATED, ENTITY_TYPE, teamId,
                Map.of("cancelledJoinRequests", cancelled));
        return assembler.toResponse(team);
    }

    /**
     * Deletes the team for good (ADMIN only). See the class comment for what blocks it and what
     * goes with it. The team row is locked first, so a concurrent change to it waits.
     */
    @Transactional
    public void delete(AuthenticatedUser actor, Long teamId) {
        if (!actor.isAdmin()) {
            throw new ForbiddenOperationException("Solo un administrador puede eliminar un equipo.");
        }
        Team team = teams.findByIdForUpdate(teamId).orElseThrow(() -> NotFoundException.of("el equipo", teamId));
        List<String> withMatches = teamMatches.tournamentsWithMatchesOf(teamId);
        if (!withMatches.isEmpty()) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' tiene partidos en "
                    + tournamentList(withMatches) + ". Elimine primero ese torneo (puede suspenderlo mientras "
                    + "tanto) y luego elimine el equipo.");
        }
        List<String> withLiveRegistration = teamRegistrations.tournamentsWithLiveRegistrationOf(teamId);
        if (!withLiveRegistration.isEmpty()) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' tiene una inscripción en revisión "
                    + "o aprobada en " + tournamentList(withLiveRegistration) + ". Rechace o retire esa inscripción, "
                    + "o elimine el torneo (puede suspenderlo mientras tanto), antes de eliminar el equipo.");
        }

        TeamRegistrationPort.DeletedRegistrations closed = teamRegistrations.deleteClosedRegistrationsOf(teamId);
        closed.fileIds().forEach(fileDeletion::deleteAfterCommit);
        fileDeletion.deleteAfterCommit(team.getPhotoFileId());
        Long captainId = team.getCaptain().getId();
        Map<String, Object> details = Map.of("name", team.getName(), "members", team.memberCount(),
                "deletedRegistrations", closed.count());
        // Memberships go with the entity; join requests and invitations through ON DELETE CASCADE.
        teams.delete(team);
        teams.flush();
        roleService.revokeCaptainForDeletedTeam(actor.id(), captainId, teamId);
        auditService.record(actor.id(), AuditAction.TEAM_DELETED, ENTITY_TYPE, teamId, details);
    }

    /**
     * Sets or replaces the team photo (captain of the team, ORGANIZER or ADMIN). Allowed at any
     * time: neither the tournament roster freeze nor a suspended tournament blocks it. The team
     * row is locked so two concurrent uploads cannot both read the same previous photo; the
     * previous binary is deleted once the change is committed.
     */
    @Transactional
    public TeamResponse replacePhoto(AuthenticatedUser actor, Long teamId, MultipartFile file) {
        Team team = requireTeamForUpdate(teamId);
        requirePhotoEditor(actor, team);
        String previous = team.getPhotoFileId();
        String fileId = fileStorage.store(FileUpload.from(file), FileKind.IMAGE, FileOwner.teamPhoto(teamId));
        team.setPhotoFileId(fileId);
        fileDeletion.deleteAfterCommit(previous);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("photoFileId", fileId);
        details.put("replaced", previous != null);
        auditService.record(actor.id(), AuditAction.TEAM_PHOTO_UPDATED, ENTITY_TYPE, teamId, details);
        return assembler.toResponse(team);
    }

    /** Removes the team photo (same actors and same "any time" rule as {@link #replacePhoto}). */
    @Transactional
    public TeamResponse removePhoto(AuthenticatedUser actor, Long teamId) {
        Team team = requireTeamForUpdate(teamId);
        requirePhotoEditor(actor, team);
        String previous = team.getPhotoFileId();
        if (previous == null) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' no tiene foto.");
        }
        team.setPhotoFileId(null);
        fileDeletion.deleteAfterCommit(previous);
        auditService.record(actor.id(), AuditAction.TEAM_PHOTO_REMOVED, ENTITY_TYPE, teamId,
                Map.of("photoFileId", previous));
        return assembler.toResponse(team);
    }

    private static void requirePhotoEditor(AuthenticatedUser actor, Team team) {
        if (!actor.isAdmin() && !actor.hasRole(Roles.ORGANIZER) && !team.isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '" + team.getName()
                    + "', un organizador o un administrador pueden cambiar su foto.");
        }
    }

    /** "el torneo «A»" or "los torneos «A», «B»". */
    private static String tournamentList(List<String> names) {
        return RosterEligibility.tournamentList(names);
    }

    @Transactional(readOnly = true)
    public TeamEligibility.Result eligibility(Long teamId) {
        Team team = requireTeam(teamId);
        return RosterEligibility.check(members(team), memberProfiles.findProfiles(team.memberIds()), Map.of());
    }

    /**
     * The members of the team with their position and jersey number (null without a sport
     * profile), in no particular order. Read model for other modules, e.g. the player export.
     */
    @Transactional(readOnly = true)
    public List<TeamRosterEntry> rosterOf(Long teamId) {
        Team team = requireTeam(teamId);
        Map<Long, MemberProfilePort.MemberProfile> profiles = memberProfiles.findProfiles(team.memberIds());
        return team.getMembers().stream()
                .map(TeamMember::getUser)
                .map(user -> {
                    var profile = profiles.get(user.getId());
                    return new TeamRosterEntry(team.getId(), team.getName(), user, team.isCaptain(user.getId()),
                            profile == null ? null : profile.position(),
                            profile == null ? null : profile.jerseyNumber());
                })
                .toList();
    }

    /**
     * Adds a player to a team enforcing the membership rules. Called by the join-request flow of
     * the players module through {@code TeamGateway}, which locks the player's row first so the
     * "not in another team" check cannot be raced by a second acceptance. The team row is locked
     * too, so two acceptances into the same team cannot both pass the size check.
     */
    @Transactional
    public void addMember(Long teamId, Long userId) {
        Team team = requireTeamForUpdate(teamId);
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
        AppUser player = userService.getUser(userId);
        List<AppUser> after = new ArrayList<>(members(team));
        after.add(player);
        requireStaysEligibleIfRegistered(team, after);
        team.addMember(player);
    }

    /**
     * Closes recruitment when the team's roster becomes frozen (the tournament where it is
     * APPROVED starts): the join requests and invitations still pending could never be accepted
     * any more, so they are cancelled instead of being left for players to trip over. The team row
     * is locked first, so a roster change already under way commits before the start does, and one
     * arriving later sees the started tournament (and the freeze).
     *
     * @return how many requests and invitations were cancelled
     */
    @Transactional
    public int closeRecruitment(Long actorUserId, Long teamId) {
        requireTeamForUpdate(teamId);
        return joinRequests.cancelPendingRequestsOf(actorUserId, teamId, ROSTER_FROZEN);
    }

    /**
     * Whether the team's roster is frozen: APPROVED in a tournament that has started (IN_PROGRESS,
     * or SUSPENDED from IN_PROGRESS).
     */
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

    /**
     * Loads the team with a database write lock: roster changes and the registration approval
     * (minimum-size re-check) take it so they cannot interleave.
     */
    @Transactional
    public Team requireTeamForUpdate(Long teamId) {
        return teams.findByIdForUpdate(teamId).orElseThrow(() -> NotFoundException.of("el equipo", teamId));
    }

    private static void requireCaptainOrAdmin(AuthenticatedUser actor, Team team) {
        if (!actor.isAdmin() && !team.isCaptain(actor.id())) {
            throw new ForbiddenOperationException("Solo el capitán del equipo '" + team.getName() + "' puede realizar esta acción.");
        }
    }

    private void requireUnlocked(Team team) {
        if (teamLock.isLocked(team.getId())) {
            throw new BusinessRuleException("El equipo '" + team.getName() + "' está jugando un torneo que ya "
                    + "inició, por lo que no se puede modificar hasta que el torneo finalice.");
        }
    }

    /**
     * While the team is APPROVED in a tournament that has not started, refuses a roster change
     * whose result would fail {@link TeamEligibility}. Called with the team row already locked.
     */
    private void requireStaysEligibleIfRegistered(Team team, List<AppUser> resultingMembers) {
        List<String> upcoming = teamLock.upcomingTournamentsOf(team.getId());
        if (upcoming.isEmpty()) {
            return;
        }
        List<Long> ids = resultingMembers.stream().map(AppUser::getId).toList();
        TeamEligibility.Result result =
                RosterEligibility.check(resultingMembers, memberProfiles.findProfiles(ids), Map.of());
        if (!result.eligible()) {
            throw new BusinessRuleException(
                    RosterEligibility.registeredTeamRefusal(team.getName(), upcoming, result.problems()));
        }
    }

    private static List<AppUser> members(Team team) {
        return team.getMembers().stream().map(TeamMember::getUser).toList();
    }

    private static List<AppUser> membersAfterRemoving(Team team, Long userId) {
        return members(team).stream().filter(user -> !user.getId().equals(userId)).toList();
    }
}

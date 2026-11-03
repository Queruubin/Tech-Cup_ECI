package edu.escuelaing.techcup.players.application;

import edu.escuelaing.techcup.identity.application.PlayerAgePolicy;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.identity.domain.Role;
import edu.escuelaing.techcup.players.api.dto.PlayerProfileRequest;
import edu.escuelaing.techcup.players.api.dto.PlayerProfileResponse;
import edu.escuelaing.techcup.players.domain.PlayerProfile;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.players.infrastructure.PlayerProfileRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import edu.escuelaing.techcup.shared.exception.ForbiddenOperationException;
import edu.escuelaing.techcup.shared.exception.NotFoundException;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.storage.FileDeletionScheduler;
import edu.escuelaing.techcup.shared.storage.FileKind;
import edu.escuelaing.techcup.shared.storage.FileOwner;
import edu.escuelaing.techcup.shared.storage.FileStorage;
import edu.escuelaing.techcup.shared.storage.FileUpload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Sport profile use cases (spec 7.2). Rules enforced here:
 * <ul>
 *   <li>A profile is created once and can never be deleted. Creating it requires an age inside
 *       the player range ({@link PlayerAgePolicy}); registration and role assignment already
 *       check it, this guards accounts created before the rule existed.</li>
 *   <li>The photo can always be replaced or removed, in a team or not, during a tournament too.
 *       A replaced or removed photo is deleted once the change is committed. An ADMIN may remove
 *       any player's photo (moderation); the owner can upload another one afterwards.</li>
 *   <li>The position can always be changed. The jersey number too, except while the player's
 *       team is locked by a tournament ({@link TeamGateway#isLocked}); inside a team it must stay
 *       unique among the teammates. A jersey change takes the team row lock, so it serialises
 *       with teammates' changes and with the registration approval that locks the team.</li>
 *   <li>The players directory lists only ACTIVE users who currently hold the PLAYER role (a profile
 *       outlives the role); the free-agent search additionally requires no active team.</li>
 * </ul>
 * Audited as PROFILE_CREATED / PROFILE_UPDATED / PLAYER_PHOTO_REMOVED_BY_ADMIN. Photos go to the
 * {@link FileStorage} port.
 */
@Service
public class PlayerProfileService {

    static final String ENTITY_TYPE = "PLAYER_PROFILE";
    static final String JERSEY_LOCKED = "No puede cambiar su dorsal mientras su equipo juega un torneo.";
    static final String NO_PHOTO = "El jugador no tiene foto de perfil.";
    /** Reason stored with an administrator's photo removal. */
    static final String MODERATION = "moderación";

    private final PlayerProfileRepository profiles;
    private final UserService userService;
    private final PlayerAgePolicy playerAgePolicy;
    private final TeamGateway teamGateway;
    private final FileStorage fileStorage;
    private final FileDeletionScheduler fileDeletion;
    private final AuditService auditService;

    public PlayerProfileService(PlayerProfileRepository profiles, UserService userService,
                                PlayerAgePolicy playerAgePolicy, TeamGateway teamGateway, FileStorage fileStorage,
                                FileDeletionScheduler fileDeletion, AuditService auditService) {
        this.profiles = profiles;
        this.userService = userService;
        this.playerAgePolicy = playerAgePolicy;
        this.teamGateway = teamGateway;
        this.fileStorage = fileStorage;
        this.fileDeletion = fileDeletion;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PlayerProfileResponse getMine(AuthenticatedUser actor) {
        return get(actor.id());
    }

    @Transactional(readOnly = true)
    public PlayerProfileResponse get(Long userId) {
        return toResponse(requireProfile(userId));
    }

    /** Creates the profile on first call, updates it afterwards (PUT semantics). */
    @Transactional
    public PlayerProfileResponse upsert(AuthenticatedUser actor, PlayerProfileRequest request) {
        Optional<PlayerProfile> existing = profiles.findById(actor.id());
        if (existing.isPresent()) {
            PlayerProfile profile = existing.get();
            if (profile.getJerseyNumber() != request.jerseyNumber()) {
                ensureJerseyChangeAllowed(actor.id(), request.jerseyNumber());
            }
            profile.setPosition(request.position());
            profile.setJerseyNumber(request.jerseyNumber());
            auditService.record(actor.id(), AuditAction.PROFILE_UPDATED, ENTITY_TYPE, profile.getUserId(),
                    Map.of("position", profile.getPosition().name(), "jerseyNumber", profile.getJerseyNumber()));
            return toResponse(profile);
        }
        AppUser user = userService.getUser(actor.id());
        playerAgePolicy.validate(user.getBirthDate());
        PlayerProfile profile = profiles.save(PlayerProfile.builder()
                .user(user)
                .position(request.position())
                .jerseyNumber(request.jerseyNumber())
                .build());
        auditService.record(actor.id(), AuditAction.PROFILE_CREATED, ENTITY_TYPE, profile.getUserId(),
                Map.of("position", profile.getPosition().name(), "jerseyNumber", profile.getJerseyNumber()));
        return toResponse(profile);
    }

    /** Stores the new photo and, once the change is committed, deletes the one it replaces. */
    @Transactional
    public PlayerProfileResponse uploadPhoto(AuthenticatedUser actor, MultipartFile file) {
        PlayerProfile profile = requireProfile(actor.id());
        String previous = profile.getPhotoFileId();
        String fileId = fileStorage.store(FileUpload.from(file), FileKind.IMAGE, FileOwner.photo(actor.id()));
        profile.setPhotoFileId(fileId);
        fileDeletion.deleteAfterCommit(previous);
        auditService.record(actor.id(), AuditAction.PROFILE_UPDATED, ENTITY_TYPE, profile.getUserId(),
                Map.of("photoFileId", fileId));
        return toResponse(profile);
    }

    /** The owner removes their own photo; the binary is deleted once the change is committed. */
    @Transactional
    public PlayerProfileResponse removeMyPhoto(AuthenticatedUser actor) {
        PlayerProfile profile = requireProfile(actor.id());
        String previous = requirePhoto(profile);
        profile.setPhotoFileId(null);
        fileDeletion.deleteAfterCommit(previous);
        auditService.record(actor.id(), AuditAction.PROFILE_UPDATED, ENTITY_TYPE, profile.getUserId(),
                Map.of("photoRemoved", true));
        return toResponse(profile);
    }

    /**
     * An ADMIN removes any player's photo (moderation). The binary is deleted once the change is
     * committed; the player may upload a new one afterwards.
     *
     * @throws ForbiddenOperationException when the actor is not an ADMIN (the endpoint already
     *                                     requires the role; this keeps the rule in the use case)
     */
    @Transactional
    public PlayerProfileResponse removePhotoAsAdmin(AuthenticatedUser actor, Long userId) {
        if (!actor.isAdmin()) {
            throw new ForbiddenOperationException("Solo un administrador puede quitar la foto de otro jugador.");
        }
        PlayerProfile profile = requireProfile(userId);
        String previous = requirePhoto(profile);
        profile.setPhotoFileId(null);
        fileDeletion.deleteAfterCommit(previous);
        auditService.record(actor.id(), AuditAction.PLAYER_PHOTO_REMOVED_BY_ADMIN, ENTITY_TYPE, userId,
                Map.of("reason", MODERATION, "photoFileId", previous));
        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public List<PlayerProfileResponse> search(Position position, boolean availableOnly) {
        List<PlayerProfile> result = availableOnly
                ? profiles.findFreeAgents(position == null ? null : position.name())
                : profiles.findAllByPosition(position);
        // The queries already filter; this guard keeps the rule in one readable, testable place.
        return result.stream().filter(PlayerProfileService::isListedPlayer).map(this::toResponse).toList();
    }

    /** Only ACTIVE users who still hold the PLAYER role appear in the players directory. */
    private static boolean isListedPlayer(PlayerProfile profile) {
        AppUser user = profile.getUser();
        return user != null && user.isActive() && user.hasRole(Role.PLAYER);
    }

    @Transactional(readOnly = true)
    public boolean hasProfile(Long userId) {
        return profiles.existsById(userId);
    }

    private PlayerProfile requireProfile(Long userId) {
        return profiles.findById(userId)
                .orElseThrow(() -> new NotFoundException("No se encontró el perfil deportivo del usuario " + userId + "."));
    }

    private static String requirePhoto(PlayerProfile profile) {
        String photo = profile.getPhotoFileId();
        if (photo == null || photo.isBlank()) {
            throw new BusinessRuleException(NO_PHOTO);
        }
        return photo;
    }

    /**
     * A player outside any team changes their jersey freely. Inside one, the team row is locked
     * first; then the team must not be locked by a tournament and no teammate may wear the number.
     */
    private void ensureJerseyChangeAllowed(Long userId, int jerseyNumber) {
        Optional<TeamGateway.TeamRef> team = teamGateway.findActiveTeamOf(userId);
        if (team.isEmpty()) {
            return;
        }
        List<Long> teammates = teamGateway.lockMemberIds(team.get().id()).stream()
                .filter(memberId -> !memberId.equals(userId))
                .toList();
        if (teamGateway.isLocked(team.get().id())) {
            throw new BusinessRuleException(JERSEY_LOCKED);
        }
        boolean taken = !teammates.isEmpty() && profiles.findAllById(teammates).stream()
                .anyMatch(teammate -> teammate.getJerseyNumber() == jerseyNumber);
        if (taken) {
            throw new BusinessRuleException("El número de camiseta " + jerseyNumber
                    + " ya está en uso en el equipo '" + team.get().name() + "'.");
        }
    }

    PlayerProfileResponse toResponse(PlayerProfile profile) {
        Optional<TeamGateway.TeamRef> team = teamGateway.findActiveTeamOf(profile.getUserId());
        return new PlayerProfileResponse(
                profile.getUserId(),
                profile.getUser().getFullName(),
                profile.getPosition(),
                profile.getJerseyNumber(),
                profile.getPhotoFileId(),
                team.map(TeamGateway.TeamRef::id).orElse(null),
                team.map(TeamGateway.TeamRef::name).orElse(null),
                team.map(ref -> teamGateway.isLocked(ref.id())).orElse(false));
    }
}

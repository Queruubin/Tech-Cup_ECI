package edu.escuelaing.techcup.players.application;

import edu.escuelaing.techcup.identity.application.PlayerAgePolicy;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.identity.domain.AppUser;
import edu.escuelaing.techcup.players.api.dto.PlayerProfileRequest;
import edu.escuelaing.techcup.players.api.dto.PlayerProfileResponse;
import edu.escuelaing.techcup.players.domain.PlayerProfile;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.players.infrastructure.PlayerProfileRepository;
import edu.escuelaing.techcup.shared.audit.AuditAction;
import edu.escuelaing.techcup.shared.audit.AuditService;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
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
 *   <li>Position, jersey number and photo may be changed only while the player is not a member
 *       of an ACTIVE team. A replaced photo is deleted once the change is committed.</li>
 *   <li>Free-agent search returns active users with a profile and no active team.</li>
 * </ul>
 * Audited as PROFILE_CREATED / PROFILE_UPDATED. Photos go to the {@link FileStorage} port.
 */
@Service
public class PlayerProfileService {

    static final String ENTITY_TYPE = "PLAYER_PROFILE";

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
            ensureNotInActiveTeam(actor.id());
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
        ensureNotInActiveTeam(actor.id());
        String previous = profile.getPhotoFileId();
        String fileId = fileStorage.store(FileUpload.from(file), FileKind.IMAGE, FileOwner.photo(actor.id()));
        profile.setPhotoFileId(fileId);
        fileDeletion.deleteAfterCommit(previous);
        auditService.record(actor.id(), AuditAction.PROFILE_UPDATED, ENTITY_TYPE, profile.getUserId(),
                Map.of("photoFileId", fileId));
        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public List<PlayerProfileResponse> search(Position position, boolean availableOnly) {
        List<PlayerProfile> result = availableOnly
                ? profiles.findFreeAgents(position == null ? null : position.name())
                : profiles.findAllByPosition(position);
        return result.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public boolean hasProfile(Long userId) {
        return profiles.existsById(userId);
    }

    private PlayerProfile requireProfile(Long userId) {
        return profiles.findById(userId)
                .orElseThrow(() -> new NotFoundException("No se encontró el perfil deportivo del usuario " + userId + "."));
    }

    private void ensureNotInActiveTeam(Long userId) {
        teamGateway.findActiveTeamOf(userId).ifPresent(team -> {
            throw new BusinessRuleException("No puede modificar su perfil deportivo mientras sea integrante del equipo '"
                    + team.name() + "'.");
        });
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
                team.map(TeamGateway.TeamRef::name).orElse(null));
    }
}

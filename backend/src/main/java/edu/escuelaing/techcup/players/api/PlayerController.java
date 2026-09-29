package edu.escuelaing.techcup.players.api;

import edu.escuelaing.techcup.players.api.dto.PlayerProfileRequest;
import edu.escuelaing.techcup.players.api.dto.PlayerProfileResponse;
import edu.escuelaing.techcup.players.application.PlayerProfileService;
import edu.escuelaing.techcup.players.domain.Position;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/players")
@Tag(name = "Players")
public class PlayerController {

    private final PlayerProfileService profileService;

    public PlayerController(PlayerProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me/profile")
    @PreAuthorize("hasRole('PLAYER')")
    public PlayerProfileResponse myProfile(@CurrentUser AuthenticatedUser actor) {
        return profileService.getMine(actor);
    }

    @PutMapping("/me/profile")
    @PreAuthorize("hasRole('PLAYER')")
    @Operation(summary = "Create or update my sport profile (locked while in an active team)")
    public PlayerProfileResponse upsertProfile(@CurrentUser AuthenticatedUser actor,
                                               @Valid @RequestBody PlayerProfileRequest request) {
        return profileService.upsert(actor, request);
    }

    @PostMapping(value = "/me/profile/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('PLAYER')")
    @Operation(summary = "Upload my profile photo (png, jpeg or webp)")
    public PlayerProfileResponse uploadPhoto(@CurrentUser AuthenticatedUser actor,
                                             @RequestPart("file") MultipartFile file) {
        return profileService.uploadPhoto(actor, file);
    }

    @GetMapping("/{userId}/profile")
    @PreAuthorize("hasAnyRole('CAPTAIN', 'ORGANIZER')")
    @Operation(summary = "A player's sport profile (captains and organizers, who recruit and review teams)")
    public PlayerProfileResponse profile(@PathVariable Long userId) {
        return profileService.get(userId);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CAPTAIN', 'ORGANIZER')")
    @Operation(summary = "Search players; available=true returns free agents (profile, no active team)")
    public List<PlayerProfileResponse> search(@RequestParam(required = false) Position position,
                                              @RequestParam(defaultValue = "false") boolean available) {
        return profileService.search(position, available);
    }
}

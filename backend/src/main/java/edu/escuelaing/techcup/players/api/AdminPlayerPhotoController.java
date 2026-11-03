package edu.escuelaing.techcup.players.api;

import edu.escuelaing.techcup.players.api.dto.PlayerProfileResponse;
import edu.escuelaing.techcup.players.application.PlayerProfileService;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Photo moderation by an administrator. Lives in the players module (the photo belongs to the
 * sport profile) under the admin users path, next to the identity module's admin endpoints.
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Admin - Users")
public class AdminPlayerPhotoController {

    private final PlayerProfileService profileService;

    public AdminPlayerPhotoController(PlayerProfileService profileService) {
        this.profileService = profileService;
    }

    @DeleteMapping("/{id}/photo")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a player's profile photo (moderation); the player may upload another one")
    public PlayerProfileResponse removePhoto(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return profileService.removePhotoAsAdmin(actor, id);
    }
}

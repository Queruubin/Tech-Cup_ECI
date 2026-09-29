package edu.escuelaing.techcup.players.api;

import edu.escuelaing.techcup.identity.api.dto.UpdateUserRequest;
import edu.escuelaing.techcup.identity.api.dto.UserResponse;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Users and players" service, user side (spec 7.2 "Actualizar usuario"). The user aggregate is
 * owned by the identity module, so this controller delegates to its application service.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "A user's profile; personal data only for the user themself or an ADMIN")
    public UserResponse get(@CurrentUser AuthenticatedUser actor, @PathVariable Long id) {
        return userService.getResponse(id, actor);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update basic info (self or ADMIN); e-mail and password are immutable")
    public UserResponse update(@CurrentUser AuthenticatedUser actor, @PathVariable Long id,
                               @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateBasicInfo(actor, id, request);
    }
}

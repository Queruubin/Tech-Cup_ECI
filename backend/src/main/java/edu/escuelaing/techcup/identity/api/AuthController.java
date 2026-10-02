package edu.escuelaing.techcup.identity.api;

import edu.escuelaing.techcup.identity.api.dto.ChangePasswordRequest;
import edu.escuelaing.techcup.identity.api.dto.LoginRequest;
import edu.escuelaing.techcup.identity.api.dto.LoginResponse;
import edu.escuelaing.techcup.identity.api.dto.RegisterRequest;
import edu.escuelaing.techcup.identity.api.dto.UserResponse;
import edu.escuelaing.techcup.identity.application.AuthService;
import edu.escuelaing.techcup.identity.application.UserService;
import edu.escuelaing.techcup.shared.security.AuthenticatedUser;
import edu.escuelaing.techcup.shared.security.CurrentUser;
import edu.escuelaing.techcup.shared.security.JwtAuthenticationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Self-registration as PLAYER or GUEST")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Authenticate with e-mail and password, returns a JWT (429 after repeated failures)")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the presented token and audit the logout")
    public void logout(@CurrentUser AuthenticatedUser actor,
                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(actor, JwtAuthenticationFilter.bearerToken(authorization).orElse(null));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change my password (the current one is required)")
    public void changePassword(@CurrentUser AuthenticatedUser actor, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(actor, request);
    }

    @GetMapping("/me")
    @Operation(summary = "Current user")
    public UserResponse me(@CurrentUser AuthenticatedUser actor) {
        return authService.me(actor);
    }
}

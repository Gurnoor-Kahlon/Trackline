package ca.trackline.api.auth;

import java.util.UUID;

import ca.trackline.api.auth.AuthDtos.Credentials;
import ca.trackline.api.auth.AuthDtos.RefreshRequest;
import ca.trackline.api.auth.AuthDtos.TokenResponse;
import ca.trackline.api.auth.AuthDtos.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registering, signing in, refreshing and signing out.
 *
 * <p>Every response is marked not to be stored: it holds tokens. These bodies are plain, not the freshness envelope the
 * transit endpoints use, since there is no feed behind them to be fresh or stale.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Accounts, access tokens and refresh tokens")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Create an account and sign in",
            description = "The password must be at least the configured length and at most 72 bytes. An address that "
                    + "already has an account is a 409.")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody Credentials credentials) {
        return noStore(HttpStatus.CREATED, service.register(credentials));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Sign in",
            description = "A wrong password and an unknown address are the same 401. Repeated failures for one address are "
                    + "refused with a 429 and a Retry-After.")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody Credentials credentials) {
        return noStore(HttpStatus.OK, service.login(credentials));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Exchange a refresh token for a new pair",
            description = "A refresh token is used once. Presenting one again after it was exchanged, beyond a short leeway, "
                    + "ends the whole sign-in and every token from it.")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return noStore(HttpStatus.OK, service.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "End a sign-in",
            description = "Ends the refresh token and every token descended from the same sign-in. The access token already "
                    + "issued stays valid until it expires, a few minutes. The answer is the same whether or not the token was known.")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        service.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in account", description = "Needs an access token.")
    public ResponseEntity<UserView> me(@AuthenticationPrincipal Jwt token) {
        return noStore(HttpStatus.OK, service.me(UUID.fromString(token.getSubject())));
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}

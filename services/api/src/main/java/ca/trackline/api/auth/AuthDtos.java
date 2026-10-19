package ca.trackline.api.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shapes sent to and from the authentication endpoints.
 *
 * <p>Request records override {@code toString} so a password or token cannot reach a log through the usual
 * "print the request" habit.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** @param email any case; stored lower-case. @param password never stored, only its hash */
    public record Credentials(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 1024) String password) {

        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    /** @param refreshToken the token last issued, which this call exchanges or ends */
    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[redacted]";
        }
    }

    public record UserView(UUID id, String email, Instant createdAt) {

        static UserView of(UserRepository.UserAccount account) {
            return new UserView(account.id(), account.email(), account.createdAt());
        }
    }

    /**
     * What signing in, registering and refreshing all return.
     *
     * @param accessToken      a signed token for the Authorization header
     * @param tokenType        always "Bearer"
     * @param expiresIn        seconds the access token is good for
     * @param refreshToken     the one token that can be exchanged for the next; use it once
     * @param refreshExpiresIn seconds the refresh token is good for
     */
    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            UserView user) {

        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }
}

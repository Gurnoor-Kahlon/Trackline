package ca.trackline.api.auth;

/**
 * A refresh token that cannot be used: unknown, expired, signed out, or presented again after it was exchanged.
 * The caller is told only that it must sign in again.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRefreshTokenException() {
        super("The session has ended. Sign in again.");
    }
}

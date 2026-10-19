package ca.trackline.api.auth;

/**
 * Sign-in failed. Deliberately says nothing about why: an unknown address and a wrong password are the same
 * answer, so the response cannot be used to find out who has an account.
 */
public class InvalidCredentialsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidCredentialsException() {
        super("Invalid email or password.");
    }
}

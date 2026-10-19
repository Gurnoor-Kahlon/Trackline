package ca.trackline.api.auth;

/**
 * A valid token for an account that no longer exists. The token is good for minutes after the account goes, so it can outlive it.
 */
public class AccountGoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountGoneException() {
        super("This account no longer exists.");
    }
}

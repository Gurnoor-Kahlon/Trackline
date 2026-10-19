package ca.trackline.api.auth;

/**
 * Registration for an address that already has an account.
 *
 * <p>This does reveal that the address is registered. Hiding it needs a confirmation email, which is not built, and a
 * registration that silently did nothing would leave the person with no account and no explanation. The cost is
 * stated in docs/authentication.md.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailAlreadyRegisteredException() {
        super("An account with this email address already exists.");
    }
}

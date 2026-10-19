package ca.trackline.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import ca.trackline.api.common.error.InvalidRequestException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes and checks passwords with BCrypt, and says what a password has to be.
 *
 * <p>BCrypt salts each hash itself, so two accounts with the same password have different hashes and a leaked table gives
 * no shortcut across them. Its cost is a setting, so the work an attacker must do per guess can rise as hardware does
 * without a migration: each hash records the cost it was made with.
 */
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder;
    private final int minLength;

    /**
     * A hash of nothing in particular, made at the same cost as real ones, so a sign-in for an address that has no
     * account takes as long as one that has. Otherwise the time taken would say which addresses are registered.
     */
    private final String decoyHash;

    public PasswordHasher(AuthProperties properties) {
        this.encoder = new BCryptPasswordEncoder(properties.bcryptCost());
        this.minLength = properties.minPasswordLength();
        byte[] noise = new byte[24];
        new SecureRandom().nextBytes(noise);
        this.decoyHash = encoder.encode(Base64.getEncoder().encodeToString(noise));
    }

    /**
     * Refuses a password that is too short, or too long for BCrypt to read all of.
     *
     * <p>BCrypt uses only the first 72 bytes. Accepting a longer password would store a hash of its beginning, and the
     * person would later sign in with anything that begins the same way. Refusing it says so instead.
     */
    public void requireAcceptable(String password) {
        if (password == null || password.codePointCount(0, password.length()) < minLength) {
            throw new InvalidRequestException("password must be at least %d characters".formatted(minLength));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > AuthProperties.MAX_PASSWORD_BYTES) {
            throw new InvalidRequestException(
                    "password must be at most %d bytes".formatted(AuthProperties.MAX_PASSWORD_BYTES));
        }
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }

    /** Spends the time of one check on nothing, for a sign-in that has no account to check against. */
    public void spendTimeOfOneCheck(String password) {
        encoder.matches(password, decoyHash);
    }
}

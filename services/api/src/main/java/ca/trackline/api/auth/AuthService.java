package ca.trackline.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import ca.trackline.api.auth.AccessTokens.Issued;
import ca.trackline.api.auth.AuthDtos.Credentials;
import ca.trackline.api.auth.AuthDtos.TokenResponse;
import ca.trackline.api.auth.AuthDtos.UserView;
import ca.trackline.api.auth.RefreshTokenRepository.StoredToken;
import ca.trackline.api.auth.UserRepository.UserAccount;
import ca.trackline.api.common.error.InvalidRequestException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registering, signing in, staying signed in and signing out.
 *
 * <p>Every sign-in issues two tokens. The access token is a signed value that lasts minutes and is checked without
 * touching the database. The refresh token is 256 random bits that lasts weeks, is stored only as a hash, and is used
 * once: exchanging it ends it and issues the next in the same family. A refresh token presented again after it was
 * exchanged is either a client that lost the reply or a thief, and the service cannot tell which, so past a short leeway
 * it ends the whole family and the person signs in again. That bounds what a stolen token is worth to one use by whoever
 * is second.
 *
 * <p>The password is hashed outside any transaction: BCrypt is slow on purpose, and a database connection held for
 * a quarter of a second per sign-in would be the first thing to run out.
 *
 * <p>Nothing here logs an address, a password or a token. Events are counted by outcome only.
 */
@Service
public class AuthService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final String EVENTS = "trackline.auth.events";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher hasher;
    private final AccessTokens accessTokens;
    private final LoginThrottle throttle;
    private final AuthProperties properties;
    private final Clock clock;
    private final MeterRegistry registry;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordHasher hasher,
            AccessTokens accessTokens,
            LoginThrottle throttle,
            AuthProperties properties,
            Clock clock,
            MeterRegistry registry,
            PlatformTransactionManager transactions) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.accessTokens = accessTokens;
        this.throttle = throttle;
        this.properties = properties;
        this.clock = clock;
        this.registry = registry;
        this.transaction = new TransactionTemplate(transactions);
    }

    public TokenResponse register(Credentials credentials) {
        String email = normalise(credentials.email());
        if (!EMAIL.matcher(email).matches()) {
            throw new InvalidRequestException("email is not a valid address");
        }
        hasher.requireAcceptable(credentials.password());
        String hash = hasher.hash(credentials.password());

        TokenResponse response = transaction.execute(status -> {
            Instant now = Instant.now(clock);
            UserAccount account = users.create(email, hash, now);
            return issue(account, UUID.randomUUID(), now);
        });
        count("register");
        return response;
    }

    public TokenResponse login(Credentials credentials) {
        String email = normalise(credentials.email());
        throttle.requireAllowed(email);

        UserAccount account = users.findByEmail(email).orElse(null);
        boolean correct;
        if (account == null) {
            // Same work as a real check, so how long this takes does not say whether the address is registered.
            hasher.spendTimeOfOneCheck(credentials.password());
            correct = false;
        } else {
            correct = hasher.matches(credentials.password(), account.passwordHash());
        }
        if (!correct) {
            throttle.recordFailure(email);
            count("login_failed");
            throw new InvalidCredentialsException();
        }

        throttle.clear(email);
        TokenResponse response = transaction.execute(status -> {
            Instant now = Instant.now(clock);
            users.recordSignIn(account.id(), now);
            return issue(account, UUID.randomUUID(), now);
        });
        count("login");
        return response;
    }

    /**
     * Exchanges a refresh token for the next one.
     *
     * <p>Whether a token is still the live one is decided by an update with the condition in it, so two requests presenting the
     * same token together cannot both win. The loser is treated as a repeat inside the leeway, which is what it is.
     */
    public TokenResponse refresh(String rawToken) {
        Outcome outcome = transaction.execute(status -> exchange(rawToken));
        if (outcome.reused()) {
            // Committed before this is thrown: ending the family has to survive the refusal.
            count("refresh_reuse");
            throw new InvalidRefreshTokenException();
        }
        count("refresh");
        return outcome.response();
    }

    private Outcome exchange(String rawToken) {
        Instant now = Instant.now(clock);
        StoredToken stored = refreshTokens.findByHash(sha256(rawToken)).orElseThrow(InvalidRefreshTokenException::new);
        if (stored.revokedAt() != null || !stored.expiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        UserAccount account = users.findById(stored.userId()).orElseThrow(InvalidRefreshTokenException::new);

        Instant rotatedAt = stored.rotatedAt();
        if (rotatedAt == null && refreshTokens.markRotated(stored.id(), now)) {
            return new Outcome(issue(account, stored.familyId(), now), false);
        }
        if (rotatedAt == null) {
            // Another request exchanged or ended it between the read and the update. If it was ended, so is this.
            StoredToken latest = refreshTokens.findByHash(sha256(rawToken)).orElseThrow(InvalidRefreshTokenException::new);
            if (latest.revokedAt() != null) {
                throw new InvalidRefreshTokenException();
            }
            rotatedAt = latest.rotatedAt() == null ? now : latest.rotatedAt();
        }

        Duration since = Duration.between(rotatedAt, now);
        if (since.compareTo(properties.refreshReuseLeeway()) <= 0) {
            // A client that lost the reply, or a second tab: give it a token of its own in the same family.
            return new Outcome(issue(account, stored.familyId(), now), false);
        }
        refreshTokens.revokeFamily(stored.familyId(), now);
        return new Outcome(null, true);
    }

    /** Ends the family a refresh token belongs to. Says nothing about whether the token was known. */
    public void logout(String rawToken) {
        transaction.executeWithoutResult(status -> refreshTokens.findByHash(sha256(rawToken))
                .ifPresent(stored -> refreshTokens.revokeFamily(stored.familyId(), Instant.now(clock))));
        count("logout");
    }

    public UserView me(UUID userId) {
        return users.findById(userId).map(UserView::of).orElseThrow(AccountGoneException::new);
    }

    private TokenResponse issue(UserAccount account, UUID familyId, Instant now) {
        Issued access = accessTokens.issue(account.id());
        String refresh = newRefreshToken();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshTokens.insert(account.id(), familyId, sha256(refresh), now, refreshExpiresAt);
        return new TokenResponse(
                access.value(),
                "Bearer",
                Duration.between(now, access.expiresAt()).toSeconds(),
                refresh,
                Duration.between(now, refreshExpiresAt).toSeconds(),
                UserView.of(account));
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform has SHA-256.
            throw new IllegalStateException(impossible);
        }
    }

    private void count(String event) {
        registry.counter(EVENTS, "event", event).increment();
    }

    private record Outcome(TokenResponse response, boolean reused) {
    }
}

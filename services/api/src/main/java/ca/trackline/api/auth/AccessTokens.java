package ca.trackline.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues and checks access tokens: short-lived signed JWTs that say who the caller is.
 *
 * <p>A token carries a user id, an issue time and an expiry, and is signed with HMAC-SHA256. Nothing else about the
 * person goes into it, since a token is readable by anyone holding it. Checking it is the framework's: the signature
 * must verify, the algorithm must be the one expected (so a token claiming "none" is refused), the issuer must match and
 * the expiry must not have passed. Expiry is read from the application clock, so a test can move time.
 *
 * <p>A token is not revocable once issued, which is why it lives minutes and not days. Signing out ends the refresh
 * token; the access token that is already out stays good until it expires.
 */
@Component
public class AccessTokens {

    /** Marks a token as an access token, so no other signed value of ours can be presented as one. */
    static final String TYPE_CLAIM = "token_use";
    static final String ACCESS_TYPE = "access";

    private final AuthProperties properties;
    private final Clock clock;
    private final SecretKey key;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public AccessTokens(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> isAccessToken = token -> ACCESS_TYPE.equals(token.getClaimAsString(TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token.", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer()), isAccessToken));
        this.decoder = nimbus;
    }

    /** An access token for a user, good from now for the configured lifetime. */
    public Issued issue(UUID userId) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(TYPE_CLAIM, ACCESS_TYPE)
                .build();
        String value = encoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Issued(value, expiresAt);
    }

    /** The decoder the filter chain checks bearer tokens with. */
    public JwtDecoder decoder() {
        return decoder;
    }

    /**
     * @param value     the token as sent to the client
     * @param expiresAt when it stops being accepted
     */
    public record Issued(String value, Instant expiresAt) {
    }
}

package ca.trackline.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

import ca.trackline.api.auth.AccessTokens.Issued;
import ca.trackline.api.testsupport.MutableClock;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Access tokens: what they carry, and the ways a token that is not one is turned away.
 */
class AccessTokensTest {

    private static final Instant START = Instant.parse("2026-10-20T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final AccessTokens tokens = new AccessTokens(AuthFixtures.properties(), clock);
    private final JwtDecoder decoder = tokens.decoder();
    private final UUID user = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** A token of our shape signed with some key, so a test can vary what is wrong with it. */
    private String signedWith(String secret, JwtClaimsSet.Builder claims) {
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private JwtClaimsSet.Builder goodClaims() {
        return JwtClaimsSet.builder()
                .issuer("trackline")
                .subject(user.toString())
                .issuedAt(START)
                .expiresAt(START.plus(Duration.ofMinutes(15)))
                .claim("token_use", "access");
    }

    @Test
    void anIssuedTokenDecodesToTheUserAndNothingElseAboutThem() {
        Jwt jwt = decoder.decode(tokens.issue(user).value());

        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        // Read as text: the issuer is a name, not a URL, which getIssuer() would insist on.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("trackline");
        assertThat(jwt.getClaims().keySet())
                .containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "token_use");
    }

    @Test
    void aTokenLastsExactlyTheConfiguredTime() {
        Issued issued = tokens.issue(user);

        assertThat(issued.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
    }

    @Test
    void aTokenIsGoodUpToTheInstantItExpiresAndRefusedAMomentAfter() {
        String token = tokens.issue(user).value();
        Instant expiry = START.plus(Duration.ofMinutes(15));

        clock.set(expiry.minusSeconds(1));
        assertThat(decoder.decode(token).getSubject()).isEqualTo(user.toString());

        // The framework accepts a token at its expiry instant and refuses it after. There is no grace on top: the
        // decoder is built with a zero clock skew, so "expires in 15 minutes" is not quietly sixteen.
        clock.set(expiry);
        assertThat(decoder.decode(token).getSubject()).isEqualTo(user.toString());

        clock.set(expiry.plusMillis(1));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void everyTokenIsDistinctEvenForTheSameUserInTheSameSecond() {
        assertThat(tokens.issue(user).value()).isNotEqualTo(tokens.issue(user).value());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        String forged = signedWith("another-secret-that-is-just-as-long-as-the-real-one-00000", goodClaims());

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenWithItsSignatureTamperedWithIsRefused() {
        String token = tokens.issue(user).value();
        String[] parts = token.split("\\.");
        // Flip a character in the middle of the signature.
        char[] signature = parts[2].toCharArray();
        signature[10] = signature[10] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signature);

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenWithItsClaimsChangedUnderTheOriginalSignatureIsRefused() {
        String token = tokens.issue(user).value();
        String[] parts = token.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = claims.replace(user.toString(), UUID.randomUUID().toString());
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenThatClaimsToHaveNoSignatureAtAllIsRefused() {
        // The classic: header says alg none, and the signature is empty.
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString((
                "{\"iss\":\"trackline\",\"sub\":\"" + user + "\",\"exp\":" + START.plusSeconds(600).getEpochSecond()
                        + ",\"token_use\":\"access\"}").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decoder.decode(header + "." + claims + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() {
        String token = signedWith(AuthFixtures.SECRET, goodClaims().issuer("somebody-else"));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aSignedValueThatIsNotAnAccessTokenIsRefused() {
        // Right key, right issuer, but marked as something else: no other value of ours can be passed off as one.
        String token = signedWith(AuthFixtures.SECRET, goodClaims().claim("token_use", "refresh"));
        String unmarked = signedWith(AuthFixtures.SECRET, JwtClaimsSet.builder()
                .issuer("trackline").subject(user.toString()).issuedAt(START).expiresAt(START.plusSeconds(600)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(unmarked)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenThatIsNotATokenIsRefused() {
        assertThatThrownBy(() -> decoder.decode("not.a.token")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("")).isInstanceOf(JwtException.class);
    }
}

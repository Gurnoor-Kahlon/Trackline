package ca.trackline.api.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Refresh tokens by their hash. The token itself is never stored, so nothing here can hand one back.
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A stored token.
     *
     * @param rotatedAt when it was exchanged for the next, or null while it is still the live one
     * @param revokedAt when its family was ended, or null
     */
    public record StoredToken(
            long id,
            UUID userId,
            UUID familyId,
            Instant expiresAt,
            Instant rotatedAt,
            Instant revokedAt) {
    }

    public void insert(UUID userId, UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                userId, familyId, tokenHash, Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    public Optional<StoredToken> findByHash(String tokenHash) {
        return jdbc.query(
                """
                SELECT id, user_id, family_id, expires_at, rotated_at, revoked_at
                FROM refresh_tokens WHERE token_hash = ?
                """,
                (rows, index) -> new StoredToken(
                        rows.getLong("id"),
                        rows.getObject("user_id", UUID.class),
                        rows.getObject("family_id", UUID.class),
                        rows.getTimestamp("expires_at").toInstant(),
                        instant(rows.getTimestamp("rotated_at")),
                        instant(rows.getTimestamp("revoked_at"))),
                tokenHash).stream().findFirst();
    }

    /**
     * Marks a token as exchanged, if it has not been already.
     *
     * <p>The condition is in the statement, so of two requests presenting the same token at the same moment exactly one
     * is told it succeeded. The other gets false, and decides what a second use means.
     *
     * @return true if this call was the one that exchanged it
     */
    public boolean markRotated(long id, Instant now) {
        return jdbc.update(
                "UPDATE refresh_tokens SET rotated_at = ? WHERE id = ? AND rotated_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(now), id) == 1;
    }

    /** Ends every token descended from one sign-in. */
    public int revokeFamily(UUID familyId, Instant now) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), familyId);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

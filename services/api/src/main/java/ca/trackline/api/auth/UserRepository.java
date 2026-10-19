package ca.trackline.api.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Accounts. Plain SQL: the table is small, nothing joins to it, and an entity would add nothing the statements
 * do not already say.
 */
@Repository
public class UserRepository {

    private static final RowMapper<UserAccount> MAPPER = (rows, index) -> new UserAccount(
            rows.getObject("id", UUID.class),
            rows.getString("email"),
            rows.getString("password_hash"),
            rows.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An account, as stored.
     *
     * @param email        trimmed and lower-case
     * @param passwordHash the BCrypt hash; never leaves the server
     */
    public record UserAccount(UUID id, String email, String passwordHash, Instant createdAt) {

        /** Excludes the hash, so a stray log line cannot print it. */
        @Override
        public String toString() {
            return "UserAccount[id=" + id + "]";
        }
    }

    /**
     * Creates an account.
     *
     * @throws EmailAlreadyRegisteredException if the address is taken. The unique index decides, not a check made first, so two
     *                                         registrations arriving together cannot both succeed
     */
    public UserAccount create(String email, String passwordHash, Instant now) {
        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?)
                    RETURNING id, email, password_hash, created_at
                    """,
                    MAPPER, email, passwordHash, Timestamp.from(now));
        } catch (DuplicateKeyException taken) {
            throw new EmailAlreadyRegisteredException();
        }
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.query(
                "SELECT id, email, password_hash, created_at FROM users WHERE email = ?", MAPPER, email)
                .stream().findFirst();
    }

    public Optional<UserAccount> findById(UUID id) {
        return jdbc.query(
                "SELECT id, email, password_hash, created_at FROM users WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public void recordSignIn(UUID id, Instant now) {
        jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?", Timestamp.from(now), id);
    }
}

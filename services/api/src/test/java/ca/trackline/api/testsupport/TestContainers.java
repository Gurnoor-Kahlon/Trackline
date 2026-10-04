package ca.trackline.api.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The containers the test run shares.
 *
 * <p>Started once for the whole build and reused by every integration test. Starting
 * a database per test class would multiply the build time without adding any
 * confidence, since Flyway gives each class the same known schema.
 *
 * <p>Real images rather than in-memory substitutes, because the behaviour under test
 * is specific to them: PostGIS spatial operators, and a cache that can be stopped.
 */
public final class TestContainers {

    public static final int REDIS_PORT = 6379;

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trackline_test")
            .withUsername("trackline")
            .withPassword("trackline");

    /**
     * The shared cache. Tests that need to take Redis away must start their own
     * container instead of stopping this one, or every class scheduled after them
     * would fail for a reason that has nothing to do with what it is testing.
     */
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private TestContainers() {
    }

    /** A Redis the caller owns and may stop. */
    public static GenericContainer<?> startDisposableRedis() {
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);
        container.start();
        return container;
    }
}

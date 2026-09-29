package ca.trackline.api.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real database.
 *
 * <p>PostGIS is not optional for Trackline: point-to-line projection, geography
 * distance, GiST indexes and trigram search have no in-memory equivalent, so
 * integration tests run against the same image as development and production.
 *
 * <p>The container is started once for the whole test run and shared, rather than
 * per class. Two suites each paying a container start would double the build time
 * for no extra confidence, and Flyway gives every class the same known schema.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgisIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("trackline_test")
            .withUsername("trackline")
            .withPassword("trackline");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

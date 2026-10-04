package ca.trackline.api.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need a real database.
 *
 * <p>PostGIS is not optional for Trackline: point-to-line projection, geography
 * distance, GiST indexes and trigram search have no in-memory equivalent, so
 * integration tests run against the same image as development and production.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgisIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", TestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> TestContainers.REDIS.getMappedPort(TestContainers.REDIS_PORT));
    }
}

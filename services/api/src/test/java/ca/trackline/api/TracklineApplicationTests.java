package ca.trackline.api;

import ca.trackline.api.config.TracklineProperties;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application now owns a schema, so a full context needs a real database:
 * Flyway migrates on startup and Hibernate validates the entity mappings against
 * the result, which is what makes a schema/entity mismatch a build failure.
 */
class TracklineApplicationTests extends PostgisIntegrationTest {

    @Autowired
    private TracklineProperties properties;

    @Test
    void contextLoads() {
        assertThat(properties).isNotNull();
    }

    @Test
    void corsOriginsAreBoundFromConfiguration() {
        assertThat(properties.cors().allowedOrigins()).containsExactly("http://localhost:3000");
    }
}

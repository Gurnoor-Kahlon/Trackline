package ca.trackline.api;

import ca.trackline.api.config.TracklineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TracklineApplicationTests {

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

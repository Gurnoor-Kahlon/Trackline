package ca.trackline.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration binding is a deployment safety control: a bad or missing value
 * must stop startup, not surface later as a broken CORS policy in production.
 */
class TracklinePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void validOriginsBind() {
        runner.withPropertyValues(
                        "trackline.cors.allowed-origins[0]=http://localhost:3000",
                        "trackline.cors.allowed-origins[1]=https://trackline.example")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(TracklineProperties.class)
                        .satisfies(properties -> assertThat(properties.cors().allowedOrigins())
                                .containsExactly("http://localhost:3000", "https://trackline.example")));
    }

    @Test
    void missingCorsConfigurationStopsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void emptyOriginListStopsStartup() {
        runner.withPropertyValues("trackline.cors.allowed-origins=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anUnresolvedPlaceholderIsNotAcceptedAsAnOrigin() {
        // Regression guard: the prod profile reads this from the environment, and a
        // missing variable previously bound as a literal and booted successfully.
        runner.withPropertyValues("trackline.cors.allowed-origins[0]=${TRACKLINE_CORS_ALLOWED_ORIGINS}")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void wildcardOriginIsRejected() {
        runner.withPropertyValues("trackline.cors.allowed-origins[0]=*")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(TracklineProperties.class)
    static class TestConfig {
    }
}

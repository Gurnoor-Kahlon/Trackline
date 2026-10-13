package ca.trackline.api.analytics.health;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The thresholds are configuration, so the configuration has to be what the documentation says
 * it is, and a set that contradicts itself has to be refused at startup and not discovered as
 * strange verdicts.
 */
class HealthPropertiesTest {

    private static HealthProperties shipped() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources all = new MutablePropertySources();
        sources.forEach(all::addLast);
        return new Binder(ConfigurationPropertySources.from(all))
                .bind("trackline.analytics.health", HealthProperties.class)
                .get();
    }

    @Test
    void theShippedConfigurationIsTheDocumentedStartingPoint() throws IOException {
        assertThat(shipped()).isEqualTo(HealthFixtures.defaults());
    }

    @Test
    void theBandsAreTheOnesDocumentedInRouteHealthMd() throws IOException {
        HealthProperties properties = shipped();

        assertThat(List.of(properties.excellentCv(), properties.unstableCv(), properties.majorCv()))
                .containsExactly(0.25, 0.5, 0.75);
        assertThat(List.of(properties.excellentGapRatio(), properties.unstableGapRatio(), properties.majorGapRatio()))
                .containsExactly(1.3, 1.8, 2.5);
        assertThat(properties.majorSupplyRatio()).isEqualTo(0.5);
        assertThat(properties.staleAfter()).isEqualTo(Duration.ofMinutes(2));
    }

    private static HealthProperties with(double excellentCv, double unstableCv, double majorCv, int min, int medium, int high) {
        return new HealthProperties(
                excellentCv, unstableCv, majorCv, 1.3, 1.8, 2.5, 0.5, min, medium, high, 3,
                Duration.ofSeconds(120), 0.3, 200, Duration.ofMinutes(15), Duration.ofMinutes(3), 3, 3, 25, 1.0);
    }

    @Test
    void variationBandsThatDoNotRiseAreRefused() {
        assertThatThrownBy(() -> with(0.5, 0.5, 0.75, 2, 3, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(0.25, 0.8, 0.75, 2, 3, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gapRatioBandsThatDoNotRiseAreRefused() {
        assertThatThrownBy(() -> new HealthProperties(
                0.25, 0.5, 0.75, 2.0, 1.8, 2.5, 0.5, 2, 3, 5, 3,
                Duration.ofSeconds(120), 0.3, 200, Duration.ofMinutes(15), Duration.ofMinutes(3), 3, 3, 25, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gap-ratio");
    }

    @Test
    void vehicleCountsThatFallAreRefused() {
        assertThatThrownBy(() -> with(0.25, 0.5, 0.75, 4, 3, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(0.25, 0.5, 0.75, 2, 6, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalCountsAreAllowed() {
        assertThat(with(0.25, 0.5, 0.75, 3, 3, 3)).isNotNull();
    }
}

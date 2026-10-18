package ca.trackline.api.analytics.bunching;

import java.io.IOException;
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
 * The thresholds are configuration, so the configuration has to be what the documentation says,
 * and a set that contradicts itself has to be refused at startup.
 */
class BunchingPropertiesTest {

    private static BunchingProperties shipped() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources all = new MutablePropertySources();
        sources.forEach(all::addLast);
        return new Binder(ConfigurationPropertySources.from(all))
                .bind("trackline.analytics.bunching", BunchingProperties.class)
                .get();
    }

    @Test
    void theShippedConfigurationIsTheDocumentedStartingPoint() throws IOException {
        assertThat(shipped()).isEqualTo(BunchingFixtures.defaults());
    }

    @Test
    void aCloseRatioOfOneOrMoreIsRefusedBecauseAGapAtTheExpectedSpacingIsNotClose() {
        assertThatThrownBy(() -> new BunchingProperties(1.0, 1.8, 2.5, 3, 3, 4, 2, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gapRatiosThatDoNotRiseAreRefused() {
        assertThatThrownBy(() -> new BunchingProperties(0.35, 2.5, 1.8, 3, 3, 4, 2, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BunchingProperties(0.35, 1.0, 2.5, 3, 3, 4, 2, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sharingMoreVehiclesThanAreEverJudgedIsRefused() {
        assertThatThrownBy(() -> new BunchingProperties(0.35, 1.8, 2.5, 3, 3, 4, 4, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needingFewerVehiclesWithoutATimetableThanWithOneIsRefused() {
        assertThatThrownBy(() -> new BunchingProperties(0.35, 1.8, 2.5, 3, 4, 3, 2, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

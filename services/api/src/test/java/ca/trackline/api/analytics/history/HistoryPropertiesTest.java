package ca.trackline.api.analytics.history;

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
 * The retention and the gate are configuration, so the configuration has to be what the documentation
 * says, and a set that would lose data or contradict itself has to be refused at startup.
 */
class HistoryPropertiesTest {

    private static HistoryProperties shipped() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources all = new MutablePropertySources();
        sources.forEach(all::addLast);
        return new Binder(ConfigurationPropertySources.from(all))
                .bind("trackline.history", HistoryProperties.class)
                .get();
    }

    private static HistoryProperties.Retention retention(
            Duration samples, Duration hourly, Duration observations, Duration events, Duration runs) {
        return new HistoryProperties.Retention(samples, hourly, observations, events, Duration.ofDays(180), runs);
    }

    @Test
    void theShippedConfigurationIsTheDocumentedStartingPoint() throws IOException {
        HistoryProperties properties = shipped();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.sampleInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.retention()).isEqualTo(retention(
                Duration.ofDays(90), Duration.ofDays(365), Duration.ofDays(14), Duration.ofDays(180),
                Duration.ofDays(30)));
        assertThat(properties.reliability()).isEqualTo(HistoryFixtures.GATE);
    }

    @Test
    void theHourlySummaryMustOutliveTheSamplesItWasBuiltFrom() {
        assertThatThrownBy(() -> retention(
                Duration.ofDays(90), Duration.ofDays(30), Duration.ofDays(14), Duration.ofDays(180), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hourly");
    }

    @Test
    void ingestionRunsMustOutliveThePositionsThatRefertoThem() {
        assertThatThrownBy(() -> retention(
                Duration.ofDays(90), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(180), Duration.ofDays(14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ingestionRuns");
    }

    @Test
    void aRetentionOfZeroIsRefusedBecauseItWouldDeleteEverythingAtOnce() {
        assertThatThrownBy(() -> retention(
                Duration.ZERO, Duration.ofDays(365), Duration.ofDays(14), Duration.ofDays(180), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSampleIntervalOfZeroIsRefused() {
        HistoryProperties good = HistoryFixtures.properties();

        assertThatThrownBy(() -> new HistoryProperties(
                true, Duration.ZERO, good.rollupInterval(), good.retentionInterval(), good.startupDelay(),
                good.retention(), good.reliability()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

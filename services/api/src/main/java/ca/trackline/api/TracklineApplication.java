package ca.trackline.api;

import ca.trackline.api.analytics.bunching.BunchingProperties;
import ca.trackline.api.analytics.health.HealthProperties;
import ca.trackline.api.analytics.history.HistoryProperties;
import ca.trackline.api.analytics.progress.ProgressProperties;
import ca.trackline.api.auth.AuthProperties;
import ca.trackline.api.config.TracklineProperties;
import ca.trackline.api.gtfs.realtime.RealtimeProperties;
import ca.trackline.api.gtfs.schedule.GtfsStaticProperties;
import ca.trackline.api.livestate.LiveStateProperties;
import ca.trackline.api.ratelimit.RateLimitProperties;
import ca.trackline.api.stream.StreamProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Realtime feeds are polled on a schedule, and open live streams are heartbeated on one.
@EnableScheduling
@EnableConfigurationProperties({TracklineProperties.class, GtfsStaticProperties.class,
        RealtimeProperties.class, LiveStateProperties.class, ProgressProperties.class,
        StreamProperties.class, HealthProperties.class, BunchingProperties.class, HistoryProperties.class,
        AuthProperties.class, RateLimitProperties.class})
public class TracklineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TracklineApplication.class, args);
    }
}

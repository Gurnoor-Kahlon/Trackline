package ca.trackline.api;

import ca.trackline.api.config.TracklineProperties;
import ca.trackline.api.gtfs.realtime.RealtimeProperties;
import ca.trackline.api.gtfs.schedule.GtfsStaticProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Realtime feeds are polled on a schedule; nothing else in the application uses it.
@EnableScheduling
@EnableConfigurationProperties({TracklineProperties.class, GtfsStaticProperties.class, RealtimeProperties.class})
public class TracklineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TracklineApplication.class, args);
    }
}

package ca.trackline.api;

import ca.trackline.api.config.TracklineProperties;
import ca.trackline.api.gtfs.schedule.GtfsStaticProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({TracklineProperties.class, GtfsStaticProperties.class})
public class TracklineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TracklineApplication.class, args);
    }
}

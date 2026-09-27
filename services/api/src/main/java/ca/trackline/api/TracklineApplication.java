package ca.trackline.api;

import ca.trackline.api.config.TracklineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TracklineProperties.class)
public class TracklineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TracklineApplication.class, args);
    }
}

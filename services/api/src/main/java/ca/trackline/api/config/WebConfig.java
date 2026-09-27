package ca.trackline.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the browser frontend. Origins come from configuration per profile —
 * localhost in development, the deployed frontend origins in production — so no
 * wildcard is ever compiled in.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TracklineProperties properties;

    public WebConfig(TracklineProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET")
                .allowedHeaders("Content-Type", "Accept", "Last-Event-ID")
                .maxAge(3600);
    }
}

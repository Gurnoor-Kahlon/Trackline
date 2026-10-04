package ca.trackline.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serialisation for the live-state cache.
 *
 * <p>Its own mapper rather than the web one. The cached document is an internal
 * format whose only reader is this application, so it should not shift because
 * someone changes how the API renders JSON — and equally, a change made for the
 * cache must not alter the public contract.
 */
@Configuration
public class RedisConfig {

    @Bean
    ObjectMapper liveStateObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Instants as ISO-8601 rather than epoch decimals, so a cached
                // document can be read by a person debugging a live problem.
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // A field added to a snapshot record must not break instances still
                // running the previous version during a rolling deploy.
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}

package ca.trackline.api.config;

import java.time.Duration;

import ca.trackline.api.gtfs.schedule.GtfsStaticProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the open-data catalogue and the archive download.
 *
 * <p>Its own client rather than a shared one: these calls talk to a third party
 * that can be slow or unreachable, and they need a read timeout suited to a 36 MB
 * download rather than to an API request. The connect timeout stays short so an
 * unreachable host fails quickly instead of holding the import open.
 */
@Configuration
public class GtfsRestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    RestClient gtfsRestClient(RestClient.Builder builder, GtfsStaticProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(properties.requestTimeout());

        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}

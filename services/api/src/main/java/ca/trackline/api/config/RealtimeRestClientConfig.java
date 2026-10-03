package ca.trackline.api.config;

import ca.trackline.api.gtfs.realtime.RealtimeProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the realtime feeds.
 *
 * <p>Separate from the schedule client because the timeouts are opposite in spirit:
 * a 36 MB archive download may legitimately take minutes, while a realtime poll
 * that has not answered in a few seconds has already missed its purpose and must
 * free the cycle rather than hold it open.
 */
@Configuration
public class RealtimeRestClientConfig {

    @Bean
    RestClient realtimeRestClient(RestClient.Builder builder, RealtimeProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}

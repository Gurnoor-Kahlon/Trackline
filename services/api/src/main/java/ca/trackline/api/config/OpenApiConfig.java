package ca.trackline.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tracklineOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Trackline API")
                .version("v1")
                .description("""
                        Real-time Toronto transit intelligence built on the TTC's public GTFS Static \
                        and GTFS-Realtime feeds.

                        Responses carrying feed-derived data are wrapped in an envelope whose `meta` \
                        block states the data's age, staleness and origin. Fields are null when a \
                        value is genuinely unknown — never zero-filled.""")
                .license(new License()
                        .name("Transit data: Open Government Licence – Toronto")
                        .url("https://open.toronto.ca/open-data-license/")));
    }
}

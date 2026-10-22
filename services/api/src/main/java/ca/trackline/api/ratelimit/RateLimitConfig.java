package ca.trackline.api.ratelimit;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Registers the rate limit as a servlet filter.
 *
 * <p>A configuration class rather than a component on the filter itself, so that the web-layer test slices, which pick up filters
 * but not configuration, are not made to supply what it needs.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    TokenBuckets requestBuckets(RateLimitProperties properties, Clock clock) {
        return new TokenBuckets(clock, properties.maxTracked());
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimitProperties properties,
            TokenBuckets requestBuckets,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties, requestBuckets, errors));
        // After the security chain (order -100), which is where the CORS headers are added.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }
}

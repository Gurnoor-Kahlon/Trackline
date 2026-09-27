package ca.trackline.api.config;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration under the {@code trackline} prefix.
 *
 * <p>Validated so a misconfigured environment fails at startup rather than at the
 * first request that depends on it. {@code @Valid} on {@code cors} is required:
 * without it the nested constraints are silently skipped and the application
 * would boot with no usable CORS configuration at all.
 *
 * @param cors browser origins permitted to call this API
 */
@Validated
@ConfigurationProperties(prefix = "trackline")
public record TracklineProperties(@NotNull @Valid Cors cors) {

    /**
     * @param allowedOrigins exact origins, never a wildcard: this API is called
     *                       from known frontend deployments only. The pattern also
     *                       rejects an unresolved {@code ${...}} placeholder, which
     *                       would otherwise be accepted as a literal origin and
     *                       leave production with silently broken CORS.
     */
    public record Cors(
            @NotEmpty
            List<
                    @Pattern(
                            regexp = "https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?",
                            message = "must be an absolute http(s) origin with no path or wildcard")
                    String>
                    allowedOrigins) {
    }
}

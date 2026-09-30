package ca.trackline.api.gtfs.schedule;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Locates the current GTFS archive in the open-data catalogue.
 *
 * <p>Resolving through the catalogue rather than hard-coding a download URL is
 * the point: the dataset's resource is replaced when the schedule changes, and a
 * pinned URL would eventually fetch something stale or 404 without anyone
 * noticing.
 */
@Component
public class GtfsFeedResolver {

    private static final Logger log = LoggerFactory.getLogger(GtfsFeedResolver.class);

    private final RestClient restClient;
    private final GtfsStaticProperties properties;

    public GtfsFeedResolver(RestClient gtfsRestClient, GtfsStaticProperties properties) {
        this.restClient = gtfsRestClient;
        this.properties = properties;
    }

    /**
     * @return the archive resource the catalogue currently advertises
     * @throws GtfsFormatException if the catalogue response has no usable ZIP
     */
    public GtfsFeedResource resolve() {
        String url = "%s?id=%s".formatted(properties.catalogueUrl(), properties.datasetId());
        JsonNode body = restClient.get().uri(url).retrieve().body(JsonNode.class);

        if (body == null || !body.path("success").asBoolean(false)) {
            throw new GtfsFormatException("Catalogue did not return a successful response for " + url);
        }

        JsonNode resources = body.path("result").path("resources");
        if (!resources.isArray() || resources.isEmpty()) {
            throw new GtfsFormatException("Catalogue returned no resources for dataset " + properties.datasetId());
        }

        List<JsonNode> archives = resources.valueStream()
                .filter(resource -> "ZIP".equalsIgnoreCase(resource.path("format").asText("")))
                .toList();

        if (archives.isEmpty()) {
            throw new GtfsFormatException("Catalogue lists no ZIP resource for dataset " + properties.datasetId());
        }
        if (archives.size() > 1) {
            // Ambiguity here would mean silently importing an arbitrary archive.
            log.warn("Catalogue lists {} ZIP resources; using the first", archives.size());
        }

        JsonNode archive = archives.getFirst();
        String downloadUrl = archive.path("url").asText(null);
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new GtfsFormatException("Catalogue resource has no download URL");
        }

        return new GtfsFeedResource(
                downloadUrl,
                archive.path("name").asText(properties.datasetId()),
                archive.path("last_modified").asText(null));
    }
}

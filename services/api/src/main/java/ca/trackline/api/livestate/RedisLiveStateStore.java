package ca.trackline.api.livestate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The live snapshot, held in Redis.
 *
 * <p>Stored as one JSON document under one key rather than a key per vehicle. The
 * map asks for every vehicle at once, so a single round trip beats 1,300 of them,
 * and the snapshot is only meaningful as a whole: a half-updated set of keys would
 * mix two feed cycles and show vehicles at positions that never coexisted.
 *
 * <p>Everything written here has a TTL. Redis holds nothing Trackline cannot
 * rebuild from the next ingestion cycle, and an expiring key is what stops a dead
 * ingester's last snapshot being served indefinitely as though it were current.
 */
@Component
public class RedisLiveStateStore implements LiveStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisLiveStateStore.class);

    static final String VEHICLES_KEY = "trackline:vehicles:snapshot";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisLiveStateStore(
            StringRedisTemplate redis,
            @Qualifier("liveStateObjectMapper") ObjectMapper objectMapper,
            LiveStateProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = properties.snapshotTtl();
    }

    @Override
    public void replaceVehicles(List<VehicleSnapshot> vehicles, Instant feedTimestamp) {
        try {
            String document = objectMapper.writeValueAsString(new StoredSnapshot(vehicles, feedTimestamp));
            redis.opsForValue().set(VEHICLES_KEY, document, ttl);
        } catch (JsonProcessingException cause) {
            // Serialisation failing is a bug, not an outage, so it is worth a loud
            // log — but still must not take down the ingestion cycle that called it.
            log.error("Could not serialise the vehicle snapshot", cause);
        } catch (RuntimeException failure) {
            // Redis being unreachable is an expected condition. Ingestion continues
            // and keeps writing history; only the cache is lost.
            log.warn("Could not write the vehicle snapshot to Redis: {}", failure.toString());
        }
    }

    @Override
    public Optional<LiveVehicles> readVehicles() {
        try {
            String document = redis.opsForValue().get(VEHICLES_KEY);
            if (document == null) {
                return Optional.empty();
            }
            StoredSnapshot stored = objectMapper.readValue(document, StoredSnapshot.class);
            return Optional.of(LiveVehicles.live(stored.vehicles(), stored.feedTimestamp()));
        } catch (JsonProcessingException cause) {
            log.error("Live snapshot in Redis could not be read back", cause);
            return Optional.empty();
        } catch (RuntimeException failure) {
            log.warn("Could not read the vehicle snapshot from Redis: {}", failure.toString());
            return Optional.empty();
        }
    }


    /**
     * What is actually stored. A record rather than the domain type so the stored
     * shape is explicit and can evolve without changing what ingestion produces.
     */
    record StoredSnapshot(List<VehicleSnapshot> vehicles, Instant feedTimestamp) {
    }
}

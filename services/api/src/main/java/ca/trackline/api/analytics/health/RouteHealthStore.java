package ca.trackline.api.analytics.health;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import ca.trackline.api.analytics.bunching.BunchingModels.SpacingSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.livestate.LiveStateProperties;
import ca.trackline.api.livestate.RedisCircuitBreaker;
import com.fasterxml.jackson.core.JsonProcessingException;
import ca.trackline.api.config.RedisConfig.LiveStateJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Where the current route health is kept and read back.
 *
 * <p>The same two tiers as the live vehicles, for the same reason: Redis is shared and
 * fast, this process's own copy covers Redis being down, and there is no database tier
 * because health is derived from a snapshot and is recomputed by the next cycle. Nothing
 * here is the only copy of anything durable; history is a later milestone's concern.
 *
 * <p>Stored as one document under one key, so a reader never sees half of one cycle's
 * verdicts beside half of another's, and with a TTL so a stopped ingester's last verdicts
 * are not served indefinitely as though they were current.
 */
@Component
public class RouteHealthStore {

    private static final Logger log = LoggerFactory.getLogger(RouteHealthStore.class);

    static final String KEY = "trackline:routehealth:snapshot";
    static final String SPACING_KEY = "trackline:routehealth:spacing";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final RedisCircuitBreaker breaker;
    private final AtomicReference<HealthSnapshot> local = new AtomicReference<>();
    private final AtomicReference<SpacingSnapshot> localSpacing = new AtomicReference<>();

    public RouteHealthStore(
            StringRedisTemplate redis,
            LiveStateJson liveStateJson,
            LiveStateProperties properties,
            RedisCircuitBreaker breaker) {
        this.redis = redis;
        this.objectMapper = liveStateJson.mapper();
        this.ttl = properties.snapshotTtl();
        this.breaker = breaker;
    }

    /** The answer to a read: the snapshot if any tier had one, and which tier it was. */
    public record HealthRead(HealthSnapshot snapshot, DataSource source, boolean degraded) {

        public static HealthRead nothing() {
            return new HealthRead(null, DataSource.NO_DATA, false);
        }
    }

    /**
     * Keeps this cycle's verdicts. The in-process copy is written first and always, so a
     * Redis failure costs the shared cache and nothing else.
     */
    public void publish(HealthSnapshot snapshot) {
        local.set(snapshot);
        try {
            String document = objectMapper.writeValueAsString(snapshot);
            breaker.run(() -> redis.opsForValue().set(KEY, document, ttl));
        } catch (JsonProcessingException cause) {
            log.error("Could not serialise the route health snapshot", cause);
        } catch (RedisCircuitBreaker.OpenException refused) {
            log.debug("Not writing route health to Redis: the circuit is open");
        } catch (RuntimeException failure) {
            log.warn("Could not write route health to Redis: {}", failure.toString());
        }
    }

    /** The answer to a read of the spacing behind the verdicts, by the same tiers. */
    public record SpacingRead(SpacingSnapshot snapshot, DataSource source, boolean degraded) {

        public static SpacingRead nothing() {
            return new SpacingRead(null, DataSource.NO_DATA, false);
        }
    }

    /**
     * Keeps the positions and bunches behind this cycle's verdicts, in its own document: the
     * verdict list is read far more often than the strip, and should not carry every vehicle.
     */
    public void publishSpacing(SpacingSnapshot snapshot) {
        localSpacing.set(snapshot);
        try {
            String document = objectMapper.writeValueAsString(snapshot);
            breaker.run(() -> redis.opsForValue().set(SPACING_KEY, document, ttl));
        } catch (JsonProcessingException cause) {
            log.error("Could not serialise the route spacing snapshot", cause);
        } catch (RedisCircuitBreaker.OpenException refused) {
            log.debug("Not writing route spacing to Redis: the circuit is open");
        } catch (RuntimeException failure) {
            log.warn("Could not write route spacing to Redis: {}", failure.toString());
        }
    }

    public SpacingRead readSpacing() {
        try {
            String document = breaker.call(() -> redis.opsForValue().get(SPACING_KEY));
            if (document != null) {
                return new SpacingRead(objectMapper.readValue(document, SpacingSnapshot.class), DataSource.LIVE, false);
            }
        } catch (JsonProcessingException cause) {
            log.error("Route spacing in Redis could not be read back", cause);
        } catch (RedisCircuitBreaker.OpenException refused) {
            // Straight to this instance's own copy.
        } catch (RuntimeException failure) {
            log.warn("Could not read route spacing from Redis: {}", failure.toString());
        }

        SpacingSnapshot held = localSpacing.get();
        return held == null ? SpacingRead.nothing() : new SpacingRead(held, DataSource.CACHED, true);
    }

    public HealthRead read() {
        try {
            String document = breaker.call(() -> redis.opsForValue().get(KEY));
            if (document != null) {
                return new HealthRead(objectMapper.readValue(document, HealthSnapshot.class), DataSource.LIVE, false);
            }
        } catch (JsonProcessingException cause) {
            log.error("Route health in Redis could not be read back", cause);
        } catch (RedisCircuitBreaker.OpenException refused) {
            // Straight to this instance's own copy.
        } catch (RuntimeException failure) {
            log.warn("Could not read route health from Redis: {}", failure.toString());
        }

        HealthSnapshot held = local.get();
        // Reaching here with a copy in memory means Redis had nothing or could not answer;
        // either way this instance's own view is what is being served, and says so.
        return held == null ? HealthRead.nothing() : new HealthRead(held, DataSource.CACHED, true);
    }

    /** Forgets this process's own copy. For tests that need an instance with no memory. */
    void forget() {
        local.set(null);
        localSpacing.set(null);
    }
}

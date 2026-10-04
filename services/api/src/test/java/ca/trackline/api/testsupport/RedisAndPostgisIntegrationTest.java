package ca.trackline.api.testsupport;

/**
 * Base for tests that exercise the live-state cache.
 *
 * <p>Identical wiring to {@link PostgisIntegrationTest} — both bases point at the
 * same shared containers — but named for what the test is about, so it is obvious
 * which classes depend on a cache being present.
 */
public abstract class RedisAndPostgisIntegrationTest extends PostgisIntegrationTest {
}

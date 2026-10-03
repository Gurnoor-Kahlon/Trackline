package ca.trackline.api.persistence.query;

import java.util.List;

import ca.trackline.api.search.SearchDtos.HitKind;
import ca.trackline.api.search.SearchDtos.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Search across routes, stops and stations.
 *
 * <p>Trigram similarity from {@code pg_trgm}, not a separate search service. The
 * corpus is a few thousand short names in a database the application already
 * talks to; adding a search engine would be a second system to run and keep in
 * sync for no benefit riders would notice.
 *
 * <p>Ranking is explicit rather than left to similarity alone, because similarity
 * ranks badly for the most common query of all. Someone typing "501" wants route
 * 501 first, but trigram similarity between "501" and "501 Queen" is low, and a
 * stop called "501 Queen St" would otherwise outrank the route itself.
 */
@Repository
public class SearchQueryRepository {

    /**
     * Below this, a trigram match is noise. Chosen so "kennedy" still finds
     * "Kennedy Station" but "xyz" finds nothing rather than something tenuous.
     */
    private static final double SIMILARITY_FLOOR = 0.2;

    /** Scores for the exact and prefix cases, kept above anything similarity returns. */
    private static final double EXACT_MATCH_SCORE = 1.0;
    private static final double PREFIX_MATCH_SCORE = 0.9;

    private static final String SEARCH = """
            WITH query AS (SELECT ?::text AS term)
            SELECT kind, id, label, sublabel, score FROM (
                SELECT 'ROUTE' AS kind, route_id AS id, short_name AS label, long_name AS sublabel,
                       CASE
                           WHEN lower(short_name) = lower(query.term) THEN ?
                           WHEN short_name ILIKE query.term || '%' THEN ?
                           ELSE similarity(short_name || ' ' || long_name, query.term)
                       END AS score
                FROM routes, query
                WHERE short_name ILIKE query.term || '%'
                   OR similarity(short_name || ' ' || long_name, query.term) >= ?

                UNION ALL

                SELECT 'STATION', id::text, name, NULL,
                       CASE WHEN lower(name) = lower(query.term) THEN ?
                                ELSE similarity(name, query.term) END
                FROM stations, query
                WHERE similarity(name, query.term) >= ?

                UNION ALL

                SELECT 'STOP', stop_id, name, code,
                       CASE WHEN lower(name) = lower(query.term) THEN ?
                                ELSE similarity(name, query.term) END
                FROM stops, query
                WHERE similarity(name, query.term) >= ?
            ) hits
            -- Routes and stations ahead of individual stops at equal score: a rider
            -- searching a name usually wants the line or the station, not one of the
            -- several hundred stops whose name mentions it.
            ORDER BY score DESC,
                     CASE kind WHEN 'ROUTE' THEN 0 WHEN 'STATION' THEN 1 ELSE 2 END,
                     label
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public SearchQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchHit> search(String term, int limit) {
        return jdbc.query(
                SEARCH,
                (rows, index) -> new SearchHit(
                        HitKind.valueOf(rows.getString("kind")),
                        rows.getString("id"),
                        rows.getString("label"),
                        rows.getString("sublabel"),
                        rows.getDouble("score")),
                term,
                EXACT_MATCH_SCORE, PREFIX_MATCH_SCORE, SIMILARITY_FLOOR,
                EXACT_MATCH_SCORE, SIMILARITY_FLOOR,
                EXACT_MATCH_SCORE, SIMILARITY_FLOOR,
                limit);
    }
}

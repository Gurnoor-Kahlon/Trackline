package ca.trackline.api.search;

/**
 * Shapes returned by search.
 */
public final class SearchDtos {

    private SearchDtos() {
    }

    /** What a hit refers to, so a client knows where to navigate. */
    public enum HitKind {
        ROUTE,
        STOP,
        STATION
    }

    /**
     * One search result.
     *
     * @param kind     what the hit is
     * @param id       the identifier to follow
     * @param label    primary text, such as a route number or stop name
     * @param sublabel supporting text, such as a route's long name
     * @param score    match quality, higher is better; exposed so a client can
     *                 decide whether a weak match deserves prominence
     */
    public record SearchHit(HitKind kind, String id, String label, String sublabel, double score) {
    }
}

package ca.trackline.api.common.envelope;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One page of results.
 *
 * <p>Carries the total so a client can show how much it has not seen. Returned as
 * the envelope's data, so freshness and provenance still come from {@code meta}.
 *
 * @param items         results on this page
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements matching rows across all pages
 * @param <T>           item type
 */
public record Page<T>(List<T> items, int page, int size, long totalElements) {

    /** Annotated because a record's extra methods are not serialised otherwise. */
    @JsonProperty
    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    @JsonProperty
    public boolean hasNext() {
        return (long) (page + 1) * size < totalElements;
    }
}

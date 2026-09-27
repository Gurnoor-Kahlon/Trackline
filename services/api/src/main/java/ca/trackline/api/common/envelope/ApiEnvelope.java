package ca.trackline.api.common.envelope;

/**
 * Standard wrapper for successful API responses: the payload plus its freshness
 * and provenance. Errors are not wrapped — they use RFC 7807 problem details.
 *
 * @param <T> payload type
 */
public record ApiEnvelope<T>(T data, ResponseMeta meta) {

    public static <T> ApiEnvelope<T> of(T data, ResponseMeta meta) {
        return new ApiEnvelope<>(data, meta);
    }
}

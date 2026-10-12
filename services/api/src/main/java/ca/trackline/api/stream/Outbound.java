package ca.trackline.api.stream;

/**
 * One server-sent event, ready to write.
 *
 * @param id   the event id a resuming client will send back, or null for events
 *             that must not move it (a heartbeat says nothing about the data)
 * @param name the SSE event name
 * @param data the JSON payload, already serialised
 */
public record Outbound(String id, String name, String data) {
}

package ca.trackline.api.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import ca.trackline.api.gtfs.realtime.AlertSnapshot;
import ca.trackline.api.gtfs.realtime.AlertSnapshot.AffectedEntity;

/**
 * A fingerprint of everything about an alert that a reader could see.
 *
 * <p>It lets a cycle tell an alert that is unchanged from one the publisher has
 * edited without comparing field by field, and lets it ignore changes that are not
 * changes: the order of the affected entities, and an entity repeated within one
 * alert, both of which the real feed does.
 *
 * <p>The publisher's identifier is left out on purpose. It says which alert this is,
 * not what the alert says.
 */
final class AlertContentHash {

    private AlertContentHash() {
    }

    static String of(AlertSnapshot alert) {
        MessageDigest digest = sha256();
        field(digest, alert.cause());
        field(digest, alert.effect());
        field(digest, alert.header());
        field(digest, alert.description());
        field(digest, alert.url());
        for (String entity : new TreeSet<>(entities(alert.affected()))) {
            field(digest, entity);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** One entry per distinct entity that names a route or a stop, in a stable form. */
    static List<String> entities(List<AffectedEntity> affected) {
        return affected.stream()
                .filter(entity -> entity.routeId() != null || entity.stopId() != null)
                .map(entity -> (entity.routeId() == null ? "" : entity.routeId())
                        + "/" + (entity.stopId() == null ? "" : entity.stopId()))
                .distinct()
                .toList();
    }

    /**
     * Each field is length-prefixed so that two different alerts cannot hash alike by
     * moving text across a field boundary, and a null is distinct from an empty string.
     */
    private static void field(MessageDigest digest, Object value) {
        byte[] bytes = value == null ? null : Objects.toString(value).getBytes(StandardCharsets.UTF_8);
        int length = bytes == null ? -1 : bytes.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException cannotHappen) {
            throw new IllegalStateException("Every Java platform provides SHA-256", cannotHappen);
        }
    }
}

package dev.andreydg.solarsystem.catalog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;

public record CatalogQuery(EventType type, BodyId bodyA, BodyId bodyB, Instant from, Instant to,
    boolean validatedOnly, boolean descending, int limit, String cursor) {
    private static final java.util.regex.Pattern RESERVED_ID = java.util.regex.Pattern.compile("__.*__", java.util.regex.Pattern.DOTALL);
    public CatalogQuery {
        CatalogLimits.checkTime(from);
        CatalogLimits.checkTime(to);
        if (!from.isBefore(to) || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid query range or limit");
        if (bodyA != null && bodyA == bodyB) throw new IllegalArgumentException("Choose two different bodies");
        if (cursor != null && !cursor.isBlank()) decode(cursor);
    }
    public Comparator<CatalogEvent> ordering() {
        var order = Comparator.comparing(CatalogEvent::displayTimeUtc).thenComparing(CatalogEvent::id);
        return descending ? order.reversed() : order;
    }
    public boolean matches(CatalogEvent event) {
        return (type == null || event.type() == type)
            && (!validatedOnly || event.validationStatus() == ValidationStatus.VALIDATED)
            && involves(event, bodyA) && involves(event, bodyB)
            && !event.displayTimeUtc().isBefore(from) && !event.displayTimeUtc().isAfter(to);
    }
    private static boolean involves(CatalogEvent event, BodyId body) {
        return body == null || event.bodyA() == body || event.bodyB() == body;
    }
    public static String pairKey(BodyId a, BodyId b) {
        return a.apiValue().compareTo(b.apiValue()) < 0 ? a.apiValue() + ":" + b.apiValue() : b.apiValue() + ":" + a.apiValue();
    }
    public static String encode(Instant time, String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
    public static Position decode(String cursor) {
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            String[] parts = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor))).toString().split("\\|", 2);
            // Firestore IDs are UTF-8 names, not application-generated identifier tokens.
            if (parts.length != 2 || parts[1].isEmpty() || parts[1].contains("/")
                || parts[1].getBytes(StandardCharsets.UTF_8).length > 1500) throw new IllegalArgumentException();
            if (parts[1].equals(".") || parts[1].equals("..") || RESERVED_ID.matcher(parts[1]).matches()) throw new IllegalArgumentException();
            Instant time = Instant.parse(parts[0]);
            CatalogLimits.checkTime(time);
            return new Position(time, parts[1]);
        } catch (RuntimeException | java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid pagination cursor");
        }
    }
    public record Position(Instant time, String id) {}
}

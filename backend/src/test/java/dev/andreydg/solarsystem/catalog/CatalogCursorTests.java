package dev.andreydg.solarsystem.catalog;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogCursorTests {
    private static final Instant TIME = Instant.parse("2027-01-01T00:00:00.123456789Z");

    @Test void cursorsRoundTripLegalFirestoreIdsIncludingUtf8ByteLimitAndDelimiters() {
        for (String id : List.of("space id", "#hash", "pipe|delimiter", "__", "a__b__c", " __x__ ", "\u0000", "legal\nname", "x".repeat(1500), "é".repeat(750), "🌌".repeat(375))) {
            assertThat(org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> CatalogQuery.decode(CatalogQuery.encode(TIME, id)))).isEqualTo(new CatalogQuery.Position(TIME, id));
        }
    }

    // Production's documented __.*__ contract includes four underscores, even where an emulator accepts them.
    // https://docs.cloud.google.com/firestore/quotas#collections_documents_and_fields
    @Test void cursorsRejectIllegalNamesOversizedIdsAndMalformedUtf8() {
        for (String id : List.of("", ".", "..", "____", "__reserved__", "__\n__", "__\r__", "__\r\n__", "__\u0085__", "__\u2028__", "__\u2029__", "__a\nb__", "a/b", "x".repeat(1501), "é".repeat(751))) {
            assertThatThrownBy(() -> CatalogQuery.decode(CatalogQuery.encode(TIME, id)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid pagination cursor");
        }
        byte[] prefix = (TIME + "|").getBytes(StandardCharsets.UTF_8);
        byte[] invalid = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        invalid[prefix.length] = (byte) 0xc3;
        invalid[prefix.length + 1] = (byte) 0x28;
        assertThatThrownBy(() -> CatalogQuery.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(invalid)))
            .hasMessage("Invalid pagination cursor");
    }
}

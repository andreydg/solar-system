package dev.andreydg.solarsystem.storage;

import static org.assertj.core.api.Assertions.*;
import dev.andreydg.solarsystem.catalog.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogNumericParsingTests {
    @Test void nonFiniteNumbersAndTheirStringRepresentationsCannotEnterCatalogGeometry() {
        var time = Instant.parse("2027-01-01T00:00:00Z");
        var event = new CatalogEvent("number", EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS,
            time, null, 180.0, null, "TEST", null, null, null, null, null, ValidationStatus.PENDING,
            time.minusSeconds(1), time.plusSeconds(1), time, null, null, null);
        for (Object value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "NaN", "Infinity", "-Infinity")) {
            var document = FirestoreEventCatalogRepository.toDocument(event);
            document.put("jplDeltaKm", value);
            assertThatThrownBy(() -> FirestoreEventCatalogRepository.fromDocument(document)).hasMessage("Non-finite catalog number");
        }
    }

    @Test void finiteNumericStringsRemainCompatibleWithLegacyCatalogRecords() {
        var time = Instant.parse("2027-01-01T00:00:00Z");
        var event = new CatalogEvent("number", EventType.OPPOSITION, BodyId.EARTH, BodyId.MARS,
            time, null, 180.0, null, "TEST", null, null, null, null, null, ValidationStatus.PENDING,
            time.minusSeconds(1), time.plusSeconds(1), time, null, null, null);
        for (String value : List.of("2.5", " 2.5 ", "0.6", "1e3")) {
            var document = FirestoreEventCatalogRepository.toDocument(event);
            document.put("jplDeltaKm", value);
            assertThat(FirestoreEventCatalogRepository.fromDocument(document).jplDeltaKm()).isEqualTo(Double.parseDouble(value));
        }
    }
}

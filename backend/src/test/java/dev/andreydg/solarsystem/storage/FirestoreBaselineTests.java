package dev.andreydg.solarsystem.storage;

import static org.assertj.core.api.Assertions.*;
import com.google.cloud.firestore.FirestoreOptions;
import dev.andreydg.solarsystem.config.EmulatorCredentials;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.FirestoreProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("firestore")
class FirestoreBaselineTests {
    @Test void repositoryRoundTripAgainstIsolatedEmulator() throws Exception {
        String host = System.getenv("FIRESTORE_EMULATOR_HOST");
        assertThat(host).matches("(127\\.0\\.0\\.1|localhost):[0-9]+");
        try (var db = FirestoreOptions.newBuilder().setProjectId("solar-system-test")
                .setHost(host).setCredentials(new EmulatorCredentials()).build().getService()) {
            var repository = new FirestoreEventCatalogRepository(db,
                new FirestoreProperties("baseline-" + java.util.UUID.randomUUID(), "(default)", host));
            var time = Instant.parse("2027-01-01T00:00:00Z");
            var event = new CatalogEvent("baseline", EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS,
                time, 1.0, null, null, "TEST", null, null, null, null, null,
                ValidationStatus.PENDING, time.minusSeconds(86400), time.plusSeconds(86400), time,
                null, null, null);
            repository.createMissing(List.of(event)).stored();
            assertThat(repository.findById(event.id())).contains(event);
        }
    }
}

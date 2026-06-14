package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.jpl.JplHorizonsClient;
import dev.andreydg.solarsystem.jpl.JplVector;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "solar-system.storage=in-memory",
    "solar-system.jpl.async-validation-enabled=false"
})
class JplHorizonsClientIntegrationTest {
    @Autowired
    private JplHorizonsClient client;

    @Test
    void fetchesHalleyVectorBatch() {
        Instant from = Instant.parse("2026-06-13T00:00:00Z");
        Instant to = Instant.parse("2076-06-13T00:00:00Z");

        List<JplVector> vectors = client.vectors(BodyId.HALLEY, from, to, "30 d");

        assertThat(vectors).isNotEmpty();
    }
}

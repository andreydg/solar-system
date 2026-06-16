package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "solar-system.storage=in-memory",
        "solar-system.jpl.async-validation-enabled=false",
        "solar-system.trajectories.cache-dir=../data/trajectories",
        "solar-system.trajectories.refresh-from-jpl=false"
    }
)
class HealthEndpointTests {

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = client().get().uri("/actuator/health").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessAndReadinessProbesAreExposed() {
        assertThat(client().get().uri("/actuator/health/liveness").retrieve().toEntity(String.class)
            .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(client().get().uri("/actuator/health/readiness").retrieve().toEntity(String.class)
            .getStatusCode().is2xxSuccessful()).isTrue();
    }
}

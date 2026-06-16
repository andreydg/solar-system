package dev.andreydg.solarsystem;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
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
class SpaForwardingTests {

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void faviconIsServedAsAFileNotTheSpaShell() {
        ResponseEntity<byte[]> response = client().get().uri("/favicon.ico").retrieve().toEntity(byte[].class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Must be the icon, not index.html forwarded by the SPA controller.
        assertThat(response.getHeaders().getContentType()).isNotEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void clientRoutesStillForwardToTheSpaShell() {
        ResponseEntity<String> response =
            client().get().uri("/some-client-route").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("<div id=\"root\">");
    }
}

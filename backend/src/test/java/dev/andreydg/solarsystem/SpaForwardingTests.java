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

    // Don't throw on 4xx/5xx — these tests assert on the status/body of error responses too.
    private RestClient client() {
        return RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (request, response) -> { })
            .build();
    }

    @Test
    void clientRoutesAreForwardedToTheSpaShell() {
        // A dotless path is a client-side route: it must render the SPA shell so deep links work.
        ResponseEntity<String> response =
            client().get().uri("/some/client/route").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody()).contains("<div id=\"root\">");
    }

    @Test
    void missingAssetLikePathsAreNotForwardedToTheSpaShell() {
        // The actual regression: a path that names a file (has an extension) but has no static
        // resource must NOT be served the SPA shell. The old controller forwarded it to
        // index.html, so a missing /favicon.ico came back as HTML and broke the favicon.
        ResponseEntity<String> response =
            client().get().uri("/definitely-not-a-real-asset.js").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
            .as("asset-like path should not resolve to the SPA shell, got %s", response.getStatusCode())
            .isFalse();
        if (response.getBody() != null) {
            assertThat(response.getBody()).doesNotContain("<div id=\"root\">");
        }
    }

    @Test
    void staticFilesAreServedWhenPresent() {
        // Sanity: a real static file (favicon fixture) is served as a file, not swallowed.
        ResponseEntity<byte[]> response =
            client().get().uri("/favicon.ico").retrieve().toEntity(byte[].class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isNotEqualTo(MediaType.TEXT_HTML);
        assertThat(response.getBody()).isNotEmpty();
    }
}

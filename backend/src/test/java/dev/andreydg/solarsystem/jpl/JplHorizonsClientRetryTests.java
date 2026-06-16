package dev.andreydg.solarsystem.jpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.JplProperties;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JplHorizonsClientRetryTests {

    private static final String VALID_BODY = String.join("\n",
        "$$SOE",
        "2461455.5, A.D. 2027-Feb-19 00:00:00.0000,  1.000000000000000E+00,  0.000000000000000E+00,  0.000000000000000E+00,",
        "$$EOE");

    private record Fixture(JplHorizonsClient client, MockRestServiceServer server) {}

    private static Fixture fixture(int maxRetries) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        JplProperties properties = new JplProperties("https://example.test/horizons", false, 4, maxRetries, 1);
        return new Fixture(new JplHorizonsClient(properties, builder), server);
    }

    @Test
    void retriesTransient5xxThenSucceeds() {
        Fixture fixture = fixture(3);
        fixture.server()
            .expect(ExpectedCount.once(), requestTo(Matchers.startsWith("https://example.test/horizons")))
            .andRespond(withServerError());
        fixture.server()
            .expect(ExpectedCount.once(), requestTo(Matchers.startsWith("https://example.test/horizons")))
            .andRespond(withSuccess(VALID_BODY, MediaType.TEXT_PLAIN));

        List<JplVector> vectors = fixture.client().vectors(
            BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z"), Instant.parse("2027-02-19T00:01:00Z"), "1m");

        assertThat(vectors).hasSize(1);
        assertThat(vectors.getFirst().positionAu().x()).isEqualTo(1.0);
        fixture.server().verify();
    }

    @Test
    void doesNotRetryPermanent4xx() {
        Fixture fixture = fixture(3);
        fixture.server()
            .expect(ExpectedCount.once(), requestTo(Matchers.startsWith("https://example.test/horizons")))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> fixture.client().vector(BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z")))
            .isInstanceOf(JplHorizonsException.class)
            .satisfies(thrown -> assertThat(((JplHorizonsException) thrown).isTransient()).isFalse());

        // Exactly one request: a 4xx is permanent, so no retry.
        fixture.server().verify();
    }

    @Test
    void givesUpAfterMaxRetriesOnPersistentTransientFailure() {
        Fixture fixture = fixture(3);
        for (int i = 0; i < 3; i++) {
            fixture.server()
                .expect(ExpectedCount.once(), requestTo(Matchers.startsWith("https://example.test/horizons")))
                .andRespond(withServerError());
        }

        assertThatThrownBy(() -> fixture.client().vector(BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z")))
            .isInstanceOf(JplHorizonsException.class)
            .satisfies(thrown -> assertThat(((JplHorizonsException) thrown).isTransient()).isTrue());

        // Three attempts total (initial + 2 retries), all 5xx.
        fixture.server().verify();
    }
}

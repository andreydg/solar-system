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
    void schedulesRatherThanInlineRetriesPartialBodyFailuresAndReleasesCapacity() {
        Fixture fixture = fixture(3);
        fixture.server().expect(requestTo(Matchers.startsWith("https://example.test/horizons"))).andRespond(request -> {
            var response = new org.springframework.mock.http.client.MockClientHttpResponse(new java.io.InputStream() {
                private boolean started;
                @Override public int read() throws java.io.IOException {
                    if (!started) { started = true; return 'x'; }
                    throw new java.net.SocketTimeoutException("dripping Horizons body timed out");
                }
            }, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
            return response;
        });
        fixture.server().expect(requestTo(Matchers.startsWith("https://example.test/horizons")))
            .andRespond(withSuccess(VALID_BODY, MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> fixture.client().vector(BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z")))
            .isInstanceOf(JplHorizonsException.class).hasMessageContaining("JPL Horizons response read failed for earth at 2027-02-19T00:00:00Z")
            .hasMessageContaining("SocketTimeoutException: dripping Horizons body timed out")
            .satisfies(failure -> {
                assertThat(((JplHorizonsException) failure).isTransient()).isTrue();
                assertThat(failure.getCause()).isInstanceOf(org.springframework.web.client.RestClientException.class);
            });
        var limiter = (java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(fixture.client(), "concurrencyLimiter");
        assertThat(limiter.availablePermits()).isEqualTo(4);
        // A later request (or the scheduled worker retry) succeeds, not a synchronous retry.
        assertThat(fixture.client().vector(BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z")).positionAu().x()).isEqualTo(1.0);
        fixture.server().verify();
    }

    @Test
    void requestsVectorsInTheJ2000EclipticFrame() {
        // The browser converts backend vectors from this frame into its equatorial scene, so the
        // frame must be pinned explicitly rather than left to Horizons defaults.
        Fixture fixture = fixture(1);
        fixture.server()
            .expect(ExpectedCount.once(), requestTo(Matchers.allOf(
                Matchers.containsString("REF_SYSTEM=%27ICRF%27"),
                Matchers.containsString("REF_PLANE=%27ECLIPTIC%27"))))
            .andRespond(withSuccess(VALID_BODY, MediaType.TEXT_PLAIN));

        fixture.client().vector(BodyId.CERES, Instant.parse("2027-02-19T00:00:00Z"));

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 404, 200})
    void budgetedPermanentErrorsStillMakeExactlyOneRequest(int status) {
        Fixture fixture = fixture(3);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        fixture.server().expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith("https://example.test/horizons")))
            .andRespond(request -> {
                requests.incrementAndGet();
                return withStatus(HttpStatus.valueOf(status)).body("No ephemeris for target").contentType(MediaType.TEXT_PLAIN).createResponse(request);
            });
        try (var budget = ValidationBudget.start(java.time.Duration.ofMinutes(1))) {
            assertThatThrownBy(() -> fixture.client().vector(BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z")))
                .isInstanceOf(JplHorizonsException.class)
                .satisfies(error -> assertThat(((JplHorizonsException) error).isTransient()).isFalse());
        }
        assertThat(requests.get()).isEqualTo(1);
        fixture.server().verify();
    }

    @Test void expiredDrainBudgetStopsBeforeTheSecondHttpAttempt() {
        Fixture fixture = fixture(3);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        fixture.server().expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith("https://example.test/horizons")))
            .andRespond(request -> {
                requests.incrementAndGet();
                // Expire the actual budget at the first response, without timing-sensitive sleeps.
                ValidationBudget.start(java.time.Duration.ZERO);
                return withServerError().createResponse(request);
            });
        try (var budget = ValidationBudget.start(java.time.Duration.ofMinutes(1))) {
            assertThatThrownBy(() -> fixture.client().vector(BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z")))
                .isInstanceOf(ValidationBudget.Exhausted.class);
        }
        assertThat(requests.get()).isEqualTo(1);
        assertThat(ValidationBudget.isActive()).isFalse();
        fixture.server().verify();
    }

    @Test void stalledBodyDiagnosticIncludesMeasuredElapsedTime() {
        Fixture fixture = fixture(3);
        fixture.server().expect(requestTo(Matchers.startsWith("https://example.test/horizons"))).andRespond(request -> {
            var response = new org.springframework.mock.http.client.MockClientHttpResponse(new java.io.InputStream() {
                @Override public int read() throws java.io.IOException {
                    try { Thread.sleep(30); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException(ex); }
                    throw new java.io.IOException("subscription cancelled");
                }
            }, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
            return response;
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> fixture.client().vector(BodyId.EARTH, Instant.parse("2027-02-19T00:00:00Z")))
            .hasMessageContaining("subscription cancelled")
            .satisfies(error -> {
                var elapsed = java.util.regex.Pattern.compile("elapsed (\\d+) ms").matcher(error.getMessage());
                assertThat(elapsed.find()).isTrue();
                assertThat(Long.parseLong(elapsed.group(1))).isBetween(20L,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            });
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

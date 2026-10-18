package dev.andreydg.solarsystem.jpl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider;
import dev.andreydg.solarsystem.storage.InMemoryEventCatalogRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.*;
import org.springframework.web.client.RestClient;

class WorkerTransportRetryTests {
    private static final Instant TIME = Instant.parse("2027-02-19T00:00:00Z");
    private static final String VALID_BODY = "$$SOE\n2461455.5, A.D. 2027-Feb-19 00:00:00.0000, 1, 0, 0,\n$$EOE";

    private static org.springframework.http.client.ClientHttpResponse brokenBody() {
        var response = new org.springframework.mock.http.client.MockClientHttpResponse(new java.io.InputStream() {
            private boolean started;
            @Override public int read() throws java.io.IOException {
                if (!started) { started = true; return 'x'; }
                throw new java.net.SocketTimeoutException("dripping transport test diagnostic");
            }
        }, HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        return response;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void exhaustedBodyReadsRetainBodyTimeAndRootCauseInPrivateLastError(boolean standalone) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new JplProperties("https://example.test/horizons", true, 4, 3, 1);
        var client = new JplHorizonsClient(properties, builder);
        server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.startsWith(properties.baseUrl())))
            .andRespond(request -> brokenBody());
        var repository = new InMemoryEventCatalogRepository();
        repository.createMissing(List.of(event("failed")));
        var catalog = new EventCatalogService(mock(CompositeEphemerisProvider.class), repository);
        var validation = mock(JplValidationService.class);
        when(validation.validateEvent("failed")).thenAnswer(call -> { client.vector(BodyId.MARS, TIME); return event("failed"); });
        var worker = new ValidationWorker(repository, catalog, validation, properties, Clock.fixed(TIME, ZoneOffset.UTC));
        if (standalone) assertThat(worker.drainOne()).isEqualTo("retry-scheduled");
        else assertThat(worker.drainBatch()).isEqualTo(new ValidationWorker.DrainResult("idle-or-busy", 1));
        assertThat(ValidationBudget.isActive()).isFalse();
        Optional<?> job = org.springframework.test.util.ReflectionTestUtils.invokeMethod(repository, "inspectJob", "failed");
        assertThat((String) org.springframework.test.util.ReflectionTestUtils.getField(job.orElseThrow(), "lastError"))
            .contains("mars", TIME.toString(), "SocketTimeoutException", "dripping transport test diagnostic", "elapsed");
        assertThat(repository.findById("failed").orElseThrow().validationStatus()).isEqualTo(ValidationStatus.PENDING);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {0.3, 0.5})
    void budgetedWorkersSurviveFlakyBodyReadsWithoutExhaustingJobs(double failureRate) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new JplProperties("https://example.test/horizons", true, 4, 3, 1);
        var client = new JplHorizonsClient(properties, builder);
        var random = new Random(0x5eed);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(properties.baseUrl())))
            .andRespond(request -> {
                requests.incrementAndGet();
                return random.nextDouble() < failureRate ? brokenBody() : withSuccess(VALID_BODY, MediaType.TEXT_PLAIN).createResponse(request);
            });
        var repository = new InMemoryEventCatalogRepository();
        var events = java.util.stream.IntStream.range(0, 40).mapToObj(i -> event("job-" + i)).toList();
        repository.createMissing(events);
        var catalog = new EventCatalogService(mock(CompositeEphemerisProvider.class), repository);
        var validation = mock(JplValidationService.class);
        when(validation.validateEvent(anyString())).thenAnswer(call -> {
            // Exercise the real transport and worker around the actual maximum request count,
            // independently of scientific geometry (covered by JplValidationServiceTests).
            for (int i = 0; i < JplValidationService.MAX_REQUESTS_PER_VALIDATION; i++) client.vector(BodyId.EARTH, TIME);
            return catalog.findById(call.getArgument(0)).orElseThrow().validated(TIME, 0.5, null, null, 0.0, "synthetic");
        });
        Instant now = TIME;
        for (int round = 0; round < ValidationWorker.MAX_ATTEMPTS; round++) {
            new ValidationWorker(repository, catalog, validation, properties, Clock.fixed(now, ZoneOffset.UTC)).drainBatch();
            now = now.plus(Duration.ofHours(6));
        }
        for (var event : events) assertThat(repository.findById(event.id()).orElseThrow().validationStatus())
            .as(event.id() + " at failure rate " + failureRate).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(requests.get()).isGreaterThan(40 * JplValidationService.MAX_REQUESTS_PER_VALIDATION);
        assertThat(ValidationBudget.isActive()).isFalse();
        server.verify();
    }

    private static CatalogEvent event(String id) {
        return new CatalogEvent(id, EventType.CLOSEST_APPROACH, BodyId.EARTH, BodyId.MARS, TIME, 0.6, null, null,
            "SYNTHETIC", null, null, null, null, null, ValidationStatus.PENDING,
            TIME.minusSeconds(100), TIME.plusSeconds(100), TIME, null, null, null);
    }
}

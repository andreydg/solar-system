package dev.andreydg.solarsystem.api;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiGuardFilterTests {
    private static final String TOKEN = "test-token-that-is-at-least-32-chars";
    @Test void administrativeRoutesFailClosed() throws Exception {
        var response = new MockHttpServletResponse();
        new ApiGuardFilter("").doFilter(new MockHttpServletRequest("POST", "/api/validation/jpl/event/x"), response,
            (request, reply) -> fail("must not execute validation"));
        assertThat(response.getStatus()).isEqualTo(503);
        response = new MockHttpServletResponse();
        new ApiGuardFilter(TOKEN).doFilter(new MockHttpServletRequest("POST", "/api/validation/jpl/event/x"), response,
            (request, reply) -> fail("must not execute unauthenticated validation"));
        assertThat(response.getStatus()).isEqualTo(401);
    }
    @Test void correctBearerTokenAllowsAdministrativeWork() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/validation/jpl/event/x");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        new ApiGuardFilter(TOKEN).doFilter(request, response, (req, res) -> res.getWriter().write("allowed"));
        assertThat(response.getContentAsString()).isEqualTo("allowed");
    }
    @Test void rateLimitHasConstantMemoryAndDoesNotTrustForwardedAddresses() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 61; i++) {
            var request = new MockHttpServletRequest("POST", "/api/events/generate");
            request.addHeader("X-Forwarded-For", "spoof-" + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(i < 60 ? 200 : 429);
            if (i == 60) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        }
    }
    @Test void trustedProxyUsesOnlyTheConfiguredSuffixAndIsolatesClients() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        for (int i = 0; i < 61; i++) {
            var request = new MockHttpServletRequest("POST", "/api/events/generate");
            request.addHeader("X-Forwarded-For", "192.0.2." + i + ", 203.0.113.1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(i < 60 ? 200 : 429);
        }
        var request = new MockHttpServletRequest("POST", "/api/events/generate");
        request.addHeader("X-Forwarded-For", "203.0.113.2");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void shortTokensDisableAdminAndNormalizedBearerWorks() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/admin/check");
        var response = new MockHttpServletResponse();
        new ApiGuardFilter("short").doFilter(request, response, (req, res) -> fail("disabled"));
        assertThat(response.getStatus()).isEqualTo(503);
        request.addHeader("Authorization", "bEaReR " + TOKEN);
        response = new MockHttpServletResponse();
        new ApiGuardFilter(TOKEN + "\n").doFilter(request, response, (req, res) -> {});
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void countsUnknownLengthBodiesAndPreservesValidJson() throws Exception {
        var filter = new ApiGuardFilter("");
        for (int bytes : new int[] {20, 200_000}) {
            var request = new MockHttpServletRequest("POST", "/api/events/generate") {
                @Override public long getContentLengthLong() { return -1; }
            };
            request.setContent("x".repeat(bytes).getBytes());
            request.addHeader("Transfer-Encoding", "chunked");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) ->
                assertThat(req.getInputStream().readAllBytes()).hasSize(20));
            assertThat(response.getStatus()).isEqualTo(bytes == 20 ? 200 : 413);
        }
    }

    @Test void fourTrajectoryReadsCanLoadWhileGenerationIsBusy() throws Exception {
        var filter = new ApiGuardFilter("");
        var started = new CountDownLatch(6);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 6; i++) {
                String path = i < 2 ? "/api/events/generate" : "/api/ephemeris/trajectory";
                futures.add(executor.submit(() -> {
                    var response = new MockHttpServletResponse();
                    filter.doFilter(new MockHttpServletRequest(iMethod(path), path), response, (req, res) -> {
                        started.countDown();
                        try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    });
                    assertThat(response.getStatus()).isEqualTo(200);
                    return null;
                }));
            }
            try { assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            for (var future : futures) future.get();
        }
    }
    private static String iMethod(String path) { return path.endsWith("generate") ? "POST" : "GET"; }

    @Test void concurrentWorkIsRejectedRatherThanQueuedAndPermitsAreReleased() throws Exception {
        var filter = new ApiGuardFilter("");
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) executor.submit(() -> {
                filter.doFilter(new MockHttpServletRequest("POST", "/api/events/generate"), new MockHttpServletResponse(),
                    (req, res) -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                return null;
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var response = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("POST", "/api/events/generate"), response,
                    (req, res) -> fail("third request must not run"));
                assertThat(response.getStatus()).isEqualTo(429);
            } finally { release.countDown(); }
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/events/generate"), response, (req, res) -> {});
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

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
    @Test void readsHaveTheirOwnLargerBudgetAndDoNotConsumeGenerationCapacity() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i=0; i<100; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/events"), response, (req,res) -> {});
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/events/generate"), response, (req,res) -> {});
        assertThat(response.getStatus()).isEqualTo(200);
    }
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
    @Test void ipv6RotationOverflowAndMalformedChainsStayBounded() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        for (int i = 1; i <= 61; i++) assertThat(status(filter, "2001:db8:1:2::" + Integer.toHexString(i))).isEqualTo(i <= 60 ? 200 : 429);
        assertThat(status(filter, "2001:db8:1:3::1")).isEqualTo(200);
        var full = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        assertThat(status(full, "198.51.100.1")).isEqualTo(200);
        for (int i = 0; i < 4095; i++) assertThat(status(full, "10.16." + (i / 256) + "." + (i % 256))).isEqualTo(200);
        for (int i = 0; i < 10; i++) assertThat(status(full, "192.0.2." + i)).isEqualTo(i < 6 ? 200 : 429);
        assertThat(status(full, "198.51.100.1")).isEqualTo(200);
        for (int i = 0; i < 7; i++) assertThat(status(filter, "x".repeat(2100))).isEqualTo(i < 6 ? 200 : 429);
        assertThat(status(filter, "203.0.113.1")).isEqualTo(200);
    }
    @Test void multiLineProxyHeadersAndExplicitCloudRunTopologyAreCovered() throws Exception {
        assertThatThrownBy(() -> new ApiGuardFilter("", -1, "deployed-service")).hasMessageContaining("TRUSTED_PROXY_HOPS");
        assertThat(ApiGuardFilter.proxyHops(-1, "")).isZero();
        var direct = new ApiGuardFilter("", 1, "deployed-service");
        for (int i = 0; i < 61; i++) assertThat(status(direct, "203.0.113.1")).isEqualTo(i < 60 ? 200 : 429);
        assertThat(status(direct, "203.0.113.2")).isEqualTo(200);
        var loadBalancer = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2);
        for (int i = 0; i < 61; i++) {
            var request = new MockHttpServletRequest("POST", "/api/events/generate");
            request.addHeader("X-Forwarded-For", "192.0.2." + i);
            request.addHeader("X-Forwarded-For", "203.0.113.1, 198.51.100.1");
            var response = new MockHttpServletResponse();
            loadBalancer.doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(i < 60 ? 200 : 429);
        }
    }
    @Test void capacityRejectionsNeverSpendQuota() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        hold(filter, "/api/events/generate", "", 2, () -> {
            for (int i = 0; i < 65; i++) assertThat(requestStatus(filter, "/api/events/generate", "")).isEqualTo(429);
        });
        for (int i = 0; i < 59; i++) assertThat(requestStatus(filter, "/api/events/generate", "")).isEqualTo(i < 58 ? 200 : 429);
    }
    @Test void coldTrajectoriesPositionsAndSmallBodyGenerationCannotBlockCatalogOrPlanetWork() throws Exception {
        var filter = new ApiGuardFilter("");
        hold(filter, "/api/ephemeris/trajectory", "", 16, () -> {
            assertThat(requestStatus(filter, "/api/ephemeris/trajectory", "")).isEqualTo(429);
            assertThat(requestStatus(filter, "/api/events/validated", "")).isEqualTo(200);
        });
        hold(filter, "/api/events/generate", "{\"bodyA\":\"earth\",\"bodyB\":\"ceres\"}", 2, () -> {
            assertThat(requestStatus(filter, "/api/events/generate", "")).isEqualTo(200);
        });
        hold(filter, "/api/ephemeris/positions", "", 8, () -> {
            assertThat(requestStatus(filter, "/api/ephemeris/positions", "")).isEqualTo(429);
            assertThat(requestStatus(filter, "/api/events/validated", "")).isEqualTo(200);
            assertThat(requestStatus(filter, "/api/events/generate", "")).isEqualTo(200);
        });
    }
    private static int status(ApiGuardFilter filter, String forwarded) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/events/generate");
        request.addHeader("X-Forwarded-For", forwarded);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response.getStatus();
    }
    private static int requestStatus(ApiGuardFilter filter, String path, String body) throws Exception {
        var request = new MockHttpServletRequest(iMethod(path), path);
        request.setContent(body.getBytes());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response.getStatus();
    }
    private interface Checked { void run() throws Exception; }
    private static void hold(ApiGuardFilter filter, String path, String body, int count, Checked inside) throws Exception {
        if (count == 0) { inside.run(); return; }
        var request = new MockHttpServletRequest(iMethod(path), path);
        request.setContent(body.getBytes());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            try { hold(filter, path, body, count - 1, inside); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test void authenticatedDrainDoesNotConsumeVisitorWorkCapacityOrQuotas() throws Exception {
        var filter = new ApiGuardFilter(TOKEN, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < 130; i++) {
            var request = new MockHttpServletRequest("POST", "/api/admin/validation/drain");
            request.addHeader("Authorization", "Bearer " + TOKEN);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(200);
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

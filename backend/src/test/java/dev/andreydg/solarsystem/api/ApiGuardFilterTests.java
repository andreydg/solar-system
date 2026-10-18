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
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) for (int bytes : new int[] {20, 200_000}) {
            var request = new MockHttpServletRequest(method, "/api/events/generate") {
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
        for (int i = 0; i < 100; i++) assertThat(status(full, "192.0.2." + i)).isEqualTo(200);
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
    @Test void mixedCaseSmallBodiesUseJplPoolAndDoNotBlockPlanetGeneration() throws Exception {
        for (String id : new String[] {"Ceres", "HALLEY", " ceres", " EnCkE "}) {
            var filter = new ApiGuardFilter("");
            hold(filter, "/api/events/generate", "{\"bodyA\":\"earth\",\"bodyB\":\"" + id + "\"}", 2,
                () -> assertThat(requestStatus(filter, "/api/events/generate", "")).isEqualTo(200));
        }
    }
    @Test void subscriberAggregateBoundsRotationWithoutCollapsingDeviceQuotas() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        int admitted = 0;
        for (int subnet = 0; subnet < 256; subnet++) {
            for (int i = 0; i < 61; i++) {
                int status = status(filter, "2001:db8:abcd:" + Integer.toHexString(0x1200 + subnet) + "::" + Integer.toHexString(i + 1));
                if (status == 200) admitted++;
                if (subnet < 80) assertThat(status).isEqualTo(i < 60 ? 200 : 429);
            }
        }
        assertThat(admitted).isEqualTo(4800);
        assertThat(status(filter, "2001:db8:abcd:1300::1")).isEqualTo(200);
        assertThat(status(filter, "198.51.100.1")).isEqualTo(200);
    }
    @Test void zoneIdentifiersUseTheMalformedBucketInsteadOfThrowing() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        for (int i = 0; i < 7; i++) assertThat(status(filter, "fe80::1%zz9")).isEqualTo(i < 6 ? 200 : 429);
    }
    @Test void cloudRunPropertyWiringFailsClosedForMissingOrZeroHops() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ApiGuardFilter.class).withPropertyValues("K_SERVICE=test-service");
        runner.run(context -> assertThat(context).hasFailed().getFailure()
            .hasRootCauseMessage("Set SOLAR_SYSTEM_TRUSTED_PROXY_HOPS for the deployed ingress (1 for direct Cloud Run)"));
        runner.withPropertyValues("SOLAR_SYSTEM_TRUSTED_PROXY_HOPS=0").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("SOLAR_SYSTEM_TRUSTED_PROXY_HOPS=1").run(context -> {
            assertThat(context).hasNotFailed();
            var filter = context.getBean(ApiGuardFilter.class);
            for (int i = 0; i < 61; i++) assertThat(status(filter, "203.0.113.1")).isEqualTo(i < 60 ? 200 : 429);
            assertThat(status(filter, "203.0.113.2")).isEqualTo(200);
        });
    }
    @Test void cachedTrajectoryBypassesColdWaitersUsingActualServiceCache() throws Exception {
        var files = org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.TrajectoryFileStore.class);
        var points = java.util.List.of(new dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample(
            Instant.EPOCH, new dev.andreydg.solarsystem.ephemeris.Vector3Au(1, 0, 0)));
        org.mockito.Mockito.when(files.load(dev.andreydg.solarsystem.catalog.BodyId.CERES)).thenReturn(java.util.Optional.of(points));
        var service = new dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService(
            org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.class), files,
            new dev.andreydg.solarsystem.config.TrajectoryCacheProperties("/unused", false));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("trajectories", service);
        var filter = new ApiGuardFilter("", 0, "", beans.getBeanProvider(dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService.class));
        service.getTrajectory(dev.andreydg.solarsystem.catalog.BodyId.CERES);
        hold(filter, "/api/ephemeris/trajectory", "", 16, () -> {
            var request = new MockHttpServletRequest("GET", "/api/ephemeris/trajectory");
            request.setParameter("body", " Ceres ");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> assertThat(service.getTrajectory(dev.andreydg.solarsystem.catalog.BodyId.CERES)).isSameAs(points));
            assertThat(response.getStatus()).isEqualTo(200);
        });
    }
    @Test void clientTableAdmits5999DistinctKeysAndOneNewcomer() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        for (int i = 0; i < 5999; i++) assertThat(status(filter, "10.0." + i / 256 + "." + i % 256)).isEqualTo(200);
        assertThat(status(filter, "203.0.113.2")).isEqualTo(200);
        assertThat(status(filter, "203.0.113.3")).isEqualTo(429); // instance backstop, not an overflow bucket
    }

    @Test void sixBusyDevicesShareASubscriberWithoutCollapsingQuotasAndTheAggregateResets() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.millis()).thenAnswer(ignored -> now.get());
        var filter = new ApiGuardFilter("", clock, 1);
        for (int device = 0; device < 6; device++) {
            String address = "2001:db8:abcd:" + Integer.toHexString(0x1200 + device) + "::1";
            for (int i = 0; i < 600; i++) assertThat(addressStatus(filter, "/api/events", address)).isEqualTo(200);
            for (int i = 0; i < 120; i++) assertThat(addressStatus(filter, "/api/ephemeris/positions", address)).isEqualTo(200);
            for (int i = 0; i < 60; i++) assertThat(addressStatus(filter, "/api/events/generate", address)).isEqualTo(200);
        }
        for (int i = 0; i < 120; i++) assertThat(addressStatus(filter, "/api/events", "2001:db8:abcd:12ff::1")).isEqualTo(200);
        assertThat(addressStatus(filter, "/api/events", "2001:db8:abcd:12ff::2")).isEqualTo(429);
        assertThat(addressStatus(filter, "/api/events", "2001:db8:abcd:1300::1")).isEqualTo(200);
        now.set(60_000);
        for (int i = 0; i < 600; i++) assertThat(addressStatus(filter, "/api/events", "2001:db8:abcd:1200::1")).isEqualTo(200);
    }

    @Test void slowWarmResponseWritesCannotExhaustCatalogReadPermits() throws Exception {
        var files = org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.TrajectoryFileStore.class);
        var points = java.util.List.of(new dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample(
            Instant.EPOCH, new dev.andreydg.solarsystem.ephemeris.Vector3Au(1, 0, 0)));
        org.mockito.Mockito.when(files.load(dev.andreydg.solarsystem.catalog.BodyId.HALLEY)).thenReturn(java.util.Optional.of(points));
        var service = new dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService(
            org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.class), files,
            new dev.andreydg.solarsystem.config.TrajectoryCacheProperties("/unused", false));
        service.getTrajectory(dev.andreydg.solarsystem.catalog.BodyId.HALLEY);
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, service::isCached);
        holdWarmResponses(filter, java.util.stream.IntStream.range(0, 16).mapToObj(i -> "203.0.113." + (i + 1)).toList(), () -> {
            for (int i = 0; i < 16; i++) assertThat(trajectoryStatus(filter, "halley")).isEqualTo(429);
            for (String path : new String[] {"/api/events", "/api/events/validated"})
                assertThat(addressStatus(filter, path, "203.0.113.99")).isEqualTo(200);
        });
        assertThat(trajectoryStatus(filter, "halley")).isEqualTo(200);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"203.0.113.1", "2001:db8:abcd:1200::1"})
    void oneSlowClientCannotMonopoliseWarmResponsesAndMinuteResetCannotBypassItsCap(String address) throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.millis()).thenAnswer(ignored -> now.get());
        var filter = new ApiGuardFilter("", clock, 1, body -> true);
        holdWarmResponses(filter, java.util.Collections.nCopies(4, address), () -> {
            assertThat(warmAddressStatus(filter, address)).isEqualTo(429);
            if (address.contains(":")) assertThat(warmAddressStatus(filter, "2001:db8:abcd:1200::ffff")).isEqualTo(429);
            assertThat(warmAddressStatus(filter, "198.51.100.2")).isEqualTo(200);
            now.set(60_000);
            // An admitted visitor triggers minute-quota rollover while the original writes remain blocked.
            assertThat(warmAddressStatus(filter, "198.51.100.2")).isEqualTo(200);
            assertThat(warmAddressStatus(filter, address)).isEqualTo(429);
        });
        holdWarmResponses(filter, java.util.Collections.nCopies(4, address), () ->
            assertThat(warmAddressStatus(filter, "198.51.100.2")).isEqualTo(200));
    }

    @Test void rotatingDevicesCannotMonopoliseWarmResponsesFromOneSubscriber() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, body -> true);
        var addresses = java.util.stream.IntStream.range(0, 8).mapToObj(i -> "2001:db8:abcd:12" + String.format("%02x", i) + "::1").toList();
        holdWarmResponses(filter, addresses, () -> {
            assertThat(warmAddressStatus(filter, "2001:db8:abcd:12ff::1")).isEqualTo(429);
            assertThat(warmAddressStatus(filter, "2001:db8:abcd:1300::1")).isEqualTo(200);
            assertThat(warmAddressStatus(filter, "203.0.113.2")).isEqualTo(200);
        });
        holdWarmResponses(filter, addresses, () ->
            assertThat(warmAddressStatus(filter, "2001:db8:abcd:1300::1")).isEqualTo(200));
    }

    @Test void warmClientPermitsAreReleasedAfterResponseFailuresAndQuotaRejections() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.millis()).thenAnswer(ignored -> now.get());
        var filter = new ApiGuardFilter("", clock, 1, body -> true);
        String address = "2001:db8:abcd:1200::1";
        for (int i = 0; i < 4; i++) assertThatThrownBy(() ->
            filter.doFilter(warmRequest(address), new MockHttpServletResponse(), (req, res) -> {
                throw new java.io.IOException("client disconnected");
            })).isInstanceOf(java.io.IOException.class);
        for (int i = 4; i < 600; i++) assertThat(warmAddressStatus(filter, address)).isEqualTo(200);
        for (int i = 0; i < 20; i++) assertThat(warmAddressStatus(filter, address)).isEqualTo(429);
        now.set(60_000);
        holdWarmResponses(filter, java.util.Collections.nCopies(4, address), () ->
            assertThat(warmAddressStatus(filter, "2001:db8:abcd:1201::1")).isEqualTo(200));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void trajectoryCapsPrecedeQuotaAndUseTrustedHopAndMalformedLimit(boolean cached) throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, body -> cached);
        String address = "2001:db8:abcd:1200::1";
        holdWarmResponses(filter, java.util.Collections.nCopies(4, address), () -> {
            for (int i = 0; i < 610; i++)
                assertThat(warmAddressStatus(filter, "192.0.2." + (i % 250) + ", " + address)).isEqualTo(429);
            assertThat(activeCounts(filter, "responseClients").values()).containsExactly(4);
            assertThat(activeCounts(filter, "responseSubscribers").values()).containsExactly(4);
        });
        for (int i = 0; i < 596; i++) assertThat(warmAddressStatus(filter, address)).isEqualTo(200);
        assertThat(warmAddressStatus(filter, address)).isEqualTo(429);
        holdWarmResponses(filter, java.util.List.of("x".repeat(2049)), () ->
            assertThat(warmAddressStatus(filter, "y".repeat(2050))).isEqualTo(429));
        assertThat(activeCounts(filter, "responseClients")).isEmpty();
        assertThat(activeCounts(filter, "responseSubscribers")).isEmpty();
    }

    @Test void responseCountsAreExactDuringCompletionAndQuotaRejection() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, body -> true);
        String address = "2001:db8:abcd:1200::1";
        holdWarmResponses(filter, java.util.Collections.nCopies(3, address), () -> {
            for (int i = 0; i < 597; i++) {
                assertThat(warmAddressStatus(filter, address)).isEqualTo(200);
                assertThat(activeCounts(filter, "responseClients").values()).containsExactly(3);
                assertThat(activeCounts(filter, "responseSubscribers").values()).containsExactly(3);
            }
            for (int i = 0; i < 5; i++) {
                assertThat(warmAddressStatus(filter, address)).isEqualTo(429);
                assertThat(activeCounts(filter, "responseClients").values()).containsExactly(3);
                assertThat(activeCounts(filter, "responseSubscribers").values()).containsExactly(3);
            }
        });
        for (int i = 0; i < 100; i++)
            assertThat(warmAddressStatus(filter, "2001:db8:" + Integer.toHexString(i) + ":1::1")).isEqualTo(200);
        assertThat(activeCounts(filter, "responseClients")).isEmpty();
        assertThat(activeCounts(filter, "responseSubscribers")).isEmpty();
    }

    @Test void globalResponseRejectionDoesNotTouchClientMaps() throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, body -> true);
        // Poisoned maps make even a read before the failed global acquire observable.
        var clients = new java.util.HashMap<String, Integer>() {
            @Override public Integer getOrDefault(Object key, Integer fallback) {
                throw new AssertionError("Global rejection must not inspect client counters");
            }
        };
        var permits = (java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(filter, "warmTrajectories");
        assertThat(permits.tryAcquire(16)).isTrue();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "responseClients", clients);
        assertThat(warmAddressStatus(filter, "203.0.113.7")).isEqualTo(429);
        assertThat(clients).isEmpty();
        assertThat(activeCounts(filter, "responseSubscribers")).isEmpty();
        assertThat(permits.availablePermits()).isZero();
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void completedResponseReleasesExactlyOneClientSlot(boolean cached) throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, body -> cached);
        String address = "203.0.113.7";
        holdWarmResponses(filter, java.util.Collections.nCopies(2, address), () -> {
            assertThat(warmAddressStatus(filter, address)).isEqualTo(200);
            assertConcurrentAdmissions(filter, address, 3, 2);
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void quotaRejectionDoesNotReleaseAnActiveClientSlot(boolean cached) throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.millis()).thenAnswer(ignored -> now.get());
        var filter = new ApiGuardFilter("", clock, 1, body -> cached);
        String address = "203.0.113.7";
        holdWarmResponses(filter, java.util.Collections.nCopies(2, address), () -> {
            for (int i = 0; i < 598; i++) assertThat(warmAddressStatus(filter, address)).isEqualTo(200);
            assertThat(warmAddressStatus(filter, address)).isEqualTo(429);
            now.set(60_000);
            assertConcurrentAdmissions(filter, address, 3, 2);
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void globalRejectionDoesNotSpendANewcomersClientSlot(boolean cached) throws Exception {
        var filter = new ApiGuardFilter("", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, body -> cached);
        var addresses = java.util.stream.IntStream.rangeClosed(1, 16).mapToObj(i -> "198.51.100." + i).toList();
        holdWarmResponses(filter, addresses, () ->
            assertThat(warmAddressStatus(filter, "203.0.113.7")).isEqualTo(429));
        assertConcurrentAdmissions(filter, "203.0.113.7", 5, 4);
    }

    // Observe admission at the servlet boundary, without looking at implementation counters.
    private static void assertConcurrentAdmissions(ApiGuardFilter filter, String address, int requests, int expected) throws Exception {
        var settled = new CountDownLatch(requests);
        var release = new CountDownLatch(1);
        var start = new CountDownLatch(1);
        var admitted = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(requests)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < requests; i++) futures.add(executor.submit(() -> {
                var entered = new java.util.concurrent.atomic.AtomicBoolean();
                var response = new MockHttpServletResponse();
                try {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    filter.doFilter(warmRequest(address), response, (req, res) -> {
                        entered.set(true); admitted.incrementAndGet(); settled.countDown();
                        try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException(ex); }
                    });
                    assertThat(response.getStatus()).isEqualTo(entered.get() ? 200 : 429);
                } finally { if (!entered.get()) settled.countDown(); }
                return null;
            }));
            start.countDown();
            try {
                assertThat(settled.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(admitted).hasValue(expected);
            } finally { release.countDown(); }
            for (var future : futures) future.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Integer> activeCounts(ApiGuardFilter filter, String field) {
        return (java.util.Map<String, Integer>) org.springframework.test.util.ReflectionTestUtils.getField(filter, field);
    }

    private static MockHttpServletRequest warmRequest(String address) {
        var request = trajectoryRequest("halley");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", address);
        return request;
    }

    private static int warmAddressStatus(ApiGuardFilter filter, String address) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(warmRequest(address), response, (req, res) -> {});
        return response.getStatus();
    }

    private static void holdWarmResponses(ApiGuardFilter filter, java.util.List<String> addresses, Checked inside) throws Exception {
        var writing = new CountDownLatch(addresses.size());
        var clientReads = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(addresses.size())) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (String address : addresses) futures.add(executor.submit(() -> {
                var response = new MockHttpServletResponse() {
                    @Override public jakarta.servlet.ServletOutputStream getOutputStream() {
                        return new jakarta.servlet.ServletOutputStream() {
                            @Override public boolean isReady() { return false; }
                            @Override public void setWriteListener(jakarta.servlet.WriteListener listener) { throw new UnsupportedOperationException(); }
                            @Override public void write(int value) throws java.io.IOException {
                                writing.countDown();
                                try { if (!clientReads.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test reader timeout"); }
                                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                            }
                        };
                    }
                };
                filter.doFilter(warmRequest(address), response, (req, res) -> res.getOutputStream().write(1));
                assertThat(response.getStatus()).isEqualTo(200);
                return null;
            }));
            try {
                assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
                inside.run();
            } finally { clientReads.countDown(); }
            for (var future : futures) future.get();
        }
    }

    @Test void uncachedAndInFlightTrajectoriesUseColdPermitsThroughTheRealService() throws Exception {
        var files = org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.TrajectoryFileStore.class);
        var points = java.util.List.of(new dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample(
            Instant.EPOCH, new dev.andreydg.solarsystem.ephemeris.Vector3Au(1, 0, 0)));
        var loading = new CountDownLatch(1);
        var finishLoad = new CountDownLatch(1);
        org.mockito.Mockito.when(files.load(dev.andreydg.solarsystem.catalog.BodyId.CERES)).thenReturn(java.util.Optional.of(points));
        org.mockito.Mockito.when(files.load(dev.andreydg.solarsystem.catalog.BodyId.HALLEY)).thenAnswer(ignored -> {
            loading.countDown();
            assertThat(finishLoad.await(10, TimeUnit.SECONDS)).isTrue();
            return java.util.Optional.of(points);
        });
        var service = new dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService(
            org.mockito.Mockito.mock(dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.class), files,
            new dev.andreydg.solarsystem.config.TrajectoryCacheProperties("/unused", false));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("trajectories", service);
        var filter = new ApiGuardFilter("", 0, "", beans.getBeanProvider(dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService.class));
        service.getTrajectory(dev.andreydg.solarsystem.catalog.BodyId.CERES);
        assertThat(service.isCached(dev.andreydg.solarsystem.catalog.BodyId.HALLEY)).isFalse();
        var entered = new CountDownLatch(16);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                final int client = i;
                futures.add(executor.submit(() -> {
                var response = new MockHttpServletResponse();
                var request = trajectoryRequest("halley");
                request.setRemoteAddr("203.0.113." + (client + 1));
                filter.doFilter(request, response, (req, res) -> {
                    entered.countDown();
                    service.getTrajectory(dev.andreydg.solarsystem.catalog.BodyId.HALLEY);
                });
                assertThat(response.getStatus()).isEqualTo(200);
                return null;
            }));
            }
            try {
                assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.isCached(dev.andreydg.solarsystem.catalog.BodyId.HALLEY)).isFalse();
                assertThat(trajectoryStatus(filter, "halley")).isEqualTo(429);
                assertThat(trajectoryStatus(filter, "ceres")).isEqualTo(200);
                assertThat(requestStatus(filter, "/api/events", "")).isEqualTo(200);
            } finally { finishLoad.countDown(); }
            for (var future : futures) future.get();
        }
        assertThat(service.isCached(dev.andreydg.solarsystem.catalog.BodyId.HALLEY)).isTrue();
    }

    private static MockHttpServletRequest trajectoryRequest(String body) {
        var request = new MockHttpServletRequest("GET", "/api/ephemeris/trajectory");
        request.setParameter("body", body);
        request.addHeader("X-Forwarded-For", "203.0.113.1");
        return request;
    }
    private static int trajectoryStatus(ApiGuardFilter filter, String body) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(trajectoryRequest(body), response, (req, res) -> {});
        return response.getStatus();
    }
    private static int addressStatus(ApiGuardFilter filter, String path, String address) throws Exception {
        var request = new MockHttpServletRequest(iMethod(path), path);
        request.addHeader("X-Forwarded-For", address);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response.getStatus();
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
        if (path.equals("/api/ephemeris/trajectory")) request.setRemoteAddr("203.0.113." + count);
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

package dev.andreydg.solarsystem.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.ephemeris.*;
import dev.andreydg.solarsystem.jpl.JplHorizonsException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicEphemerisFailureTests {
    @Test void publicPositionHidesTransportDetailsOnTheRealController() throws Exception {
        var provider = mock(CompositeEphemerisProvider.class);
        var trajectories = mock(SmallBodyTrajectoryService.class);
        when(provider.heliocentricPosition(eq(BodyId.CERES), any(Instant.class)))
            .thenThrow(new JplHorizonsException("private Spring extraction detail", new RuntimeException("secret cause"), true));
        var mvc = MockMvcBuilders.standaloneSetup(new EphemerisController(provider, trajectories))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/ephemeris/position").param("body", "ceres").param("time", "2027-02-19T00:00:00Z"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.message").value("Ephemeris temporarily unavailable. Please try again later."))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void publicAndAdminPositionMessagesDistinguishTransientFromPermanentFailures(boolean transientFailure) throws Exception {
        var failure = new JplHorizonsException("private upstream detail", new RuntimeException("secret cause"), transientFailure);
        var provider = mock(CompositeEphemerisProvider.class);
        when(provider.heliocentricPosition(eq(BodyId.CERES), any())).thenThrow(failure);
        var validation = mock(dev.andreydg.solarsystem.jpl.JplValidationService.class);
        when(validation.position(eq(BodyId.CERES), any())).thenThrow(failure);
        var mvc = MockMvcBuilders.standaloneSetup(new EphemerisController(provider, mock(SmallBodyTrajectoryService.class)),
            new JplValidationController(validation, mock(dev.andreydg.solarsystem.catalog.ValidationJobs.class),
                mock(dev.andreydg.solarsystem.jpl.ValidationWorker.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String path : new String[] {"/api/ephemeris/position", "/api/validation/jpl/position"}) {
            mvc.perform(get(path).param("body", "ceres").param("time", "2027-02-19T00:00:00Z"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(transientFailure
                    ? "Ephemeris temporarily unavailable. Please try again later."
                    : "Ephemeris request could not be completed."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        }
    }

    @Test void permanentJplFailuresLogErrorWithTheThrowable() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        var failure = new JplHorizonsException("permanent diagnostic", new IllegalArgumentException("root cause"), false);
        try {
            new GlobalExceptionHandler().handleJplException(failure);
            org.assertj.core.api.Assertions.assertThat(appender.list).singleElement().satisfies(entry -> {
                org.assertj.core.api.Assertions.assertThat(entry.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                org.assertj.core.api.Assertions.assertThat(entry.getThrowableProxy()).isNotNull();
                org.assertj.core.api.Assertions.assertThat(((ch.qos.logback.classic.spi.ThrowableProxy) entry.getThrowableProxy()).getThrowable()).isSameAs(failure);
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void transientJplFailuresLogOneWarningWithoutAStackTrace() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new GlobalExceptionHandler().handleJplException(
                new JplHorizonsException("transport diagnostic", new java.io.IOException("root cause"), true));
            org.assertj.core.api.Assertions.assertThat(appender.list).hasSize(1);
            var entry = appender.list.getFirst();
            org.assertj.core.api.Assertions.assertThat(entry.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            org.assertj.core.api.Assertions.assertThat(entry.getThrowableProxy()).isNull();
            org.assertj.core.api.Assertions.assertThat(entry.getFormattedMessage()).contains("transport diagnostic");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}

package dev.andreydg.solarsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class DisconnectedClientTests {
    @RestController static class Controller {
        @GetMapping("/serialize") java.util.Map<String, String> serialize() {
            return java.util.Map.of("payload", "x".repeat(100_000));
        }
        @GetMapping("/bug") void bug() { throw new IllegalStateException("genuine application failure"); }
        @GetMapping("/eof") void eof() throws java.io.EOFException { throw new java.io.EOFException("upstream data ended"); }
        @GetMapping("/abort") void abort() throws Exception {
            throw new org.apache.catalina.connector.ClientAbortException("connection reset");
        }
        @GetMapping("/timeout") void timeout() throws Exception {
            throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException("response write timed out");
        }
    }
    @ParameterizedTest @ValueSource(strings = {"/abort", "/timeout"})
    void disconnectedResponseLogsOneWarningWithoutAStackOrAnotherWrite(String path) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).setControllerAdvice(new GlobalExceptionHandler()).build();
            var result = mvc.perform(get(path)).andReturn();
            assertThat(result.getResponse().getContentAsString()).isEmpty();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).contains("Client response closed");
            });
        } finally { logger.detachAppender(appender); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realJsonWriteDisconnectsLogOnceWithoutAStackOrAnotherWrite(boolean timeout) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var serializations = new java.util.concurrent.atomic.AtomicInteger();
        var converter = new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter() {
            @Override protected void writeInternal(Object value, org.springframework.core.ResolvableType type,
                    org.springframework.http.HttpOutputMessage output, java.util.Map<String, Object> hints) throws java.io.IOException {
                serializations.incrementAndGet();
                super.writeInternal(value, type, output, hints);
            }
        };
        try {
            // Exercise the real MVC/Jackson write path. Only the socket output is substituted.
            var mvc = MockMvcBuilders.standaloneSetup(new Controller())
                .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(converter)
                .addFilter((request, response, chain) -> chain.doFilter(request,
                    new jakarta.servlet.http.HttpServletResponseWrapper((jakarta.servlet.http.HttpServletResponse) response) {
                        @Override public jakarta.servlet.ServletOutputStream getOutputStream() {
                            return new jakarta.servlet.ServletOutputStream() {
                                @Override public boolean isReady() { return true; }
                                @Override public void setWriteListener(jakarta.servlet.WriteListener listener) {}
                                @Override public void write(int value) throws java.io.IOException {
                                    writes.incrementAndGet();
                                    throw new org.apache.catalina.connector.ClientAbortException(timeout
                                        ? new java.net.SocketTimeoutException("write timed out")
                                        : new java.io.IOException("Broken pipe"));
                                }
                            };
                        }
                    })).build();
            var result = mvc.perform(get("/serialize")).andReturn();
            assertThat(result.getResolvedException()).isInstanceOf(org.springframework.http.converter.HttpMessageNotWritableException.class);
            var causes = new java.util.ArrayList<Class<?>>();
            for (Throwable cause = result.getResolvedException(); cause != null; cause = cause.getCause())
                causes.add(cause.getClass());
            assertThat(causes).contains(tools.jackson.databind.DatabindException.class,
                org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
                org.apache.catalina.connector.ClientAbortException.class,
                timeout ? java.net.SocketTimeoutException.class : java.io.IOException.class);
            assertThat(writes).hasValue(1);
            assertThat(serializations).hasValue(1);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).contains("Client response closed");
            });
        } finally { logger.detachAppender(appender); }
    }

    @ParameterizedTest @ValueSource(strings = {"/bug", "/eof"})
    void genuineErrorsIncludingBareEofKeepErrorLevelAndThrowable(String path) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).setControllerAdvice(new GlobalExceptionHandler()).build();
            var result = mvc.perform(get(path)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(500);
            assertThat(result.getResponse().getContentAsString()).contains("An unexpected error occurred");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(result.getResolvedException().getClass().getName());
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo(result.getResolvedException().getMessage());
            });
        } finally { logger.detachAppender(appender); }
    }
}

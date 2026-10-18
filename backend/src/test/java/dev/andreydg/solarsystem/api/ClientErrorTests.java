package dev.andreydg.solarsystem.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

class ClientErrorTests {
    @Test void mediaErrorsRemainClientErrorsAndJsonDetailsAreNotExposed() {
        var handler = new GlobalExceptionHandler();
        assertThat(handler.handleMedia(new org.springframework.web.HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, java.util.List.of(MediaType.APPLICATION_JSON))).getStatusCode().value()).isEqualTo(415);
        assertThat(handler.handleMedia(new org.springframework.web.HttpMediaTypeNotAcceptableException(java.util.List.of(MediaType.APPLICATION_JSON))).getStatusCode().value()).isEqualTo(406);
        var response = handler.handleUnreadable(new HttpMessageNotReadableException("internal DTO secret input", new MockHttpInputMessage(new byte[0])));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid JSON request body");
    }
}

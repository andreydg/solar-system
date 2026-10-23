package dev.andreydg.solarsystem.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

class ClientErrorTests {
    @Test void reservedCursorLineTerminatorsReturn400OnBothEndpointsBeforeServiceAccess() throws Exception {
        var service = org.mockito.Mockito.mock(dev.andreydg.solarsystem.catalog.EventCatalogService.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new EventCatalogController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String path : java.util.List.of("/api/events", "/api/events/validated"))
            for (String id : java.util.List.of("__\n__", "__\r__", "__\r\n__", "__\u0085__", "__\u2028__", "__\u2029__", "__a\nb__")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .param("type", "opposition").param("bodyA", "earth").param("bodyB", "mars")
                    .param("from", "2026-01-01T00:00:00Z").param("to", "2028-01-01T00:00:00Z")
                    .param("cursor", dev.andreydg.solarsystem.catalog.CatalogQuery.encode(java.time.Instant.parse("2027-01-01T00:00:00Z"), id)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test void mediaErrorsRemainClientErrorsAndJsonDetailsAreNotExposed() {
        var handler = new GlobalExceptionHandler();
        assertThat(handler.handleMedia(new org.springframework.web.HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, java.util.List.of(MediaType.APPLICATION_JSON))).getStatusCode().value()).isEqualTo(415);
        assertThat(handler.handleMedia(new org.springframework.web.HttpMediaTypeNotAcceptableException(java.util.List.of(MediaType.APPLICATION_JSON))).getStatusCode().value()).isEqualTo(406);
        var response = handler.handleUnreadable(new HttpMessageNotReadableException("internal DTO secret input", new MockHttpInputMessage(new byte[0])));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("message")).isEqualTo("Invalid JSON request body");
    }
}

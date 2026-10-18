package dev.andreydg.solarsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import dev.andreydg.solarsystem.jpl.JplHorizonsException;
import dev.andreydg.solarsystem.storage.EventCatalogStorageException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(OutputCaptureExtension.class)
class ExceptionAcceptMatrixTests {
    @RestController static class FailingEndpoints {
        record Input(@NotNull String value) {}
        @PostMapping("/probe/validation") String validation(@Valid @RequestBody Input input) { return input.value(); }
        @PostMapping("/method-only") String method() { return "ok"; }
        @GetMapping("/probe/{kind}") String failure(@PathVariable String kind) {
            throw switch (kind) {
                case "status" -> new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "Test status");
                case "jpl" -> new JplHorizonsException("Upstream failed", true);
                case "storage" -> new EventCatalogStorageException("Storage failed", new RuntimeException("private"));
                default -> new IllegalStateException("Unexpected failure");
            };
        }
    }

    @Test void everyExceptionHandlerPreservesItsStatusAndJsonForEveryAccept(CapturedOutput output) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingEndpoints()).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String accept : new String[] {"application/json", "text/html", "image/png", "application/xml", "*/*"}) {
            for (var item : java.util.Map.of("method", 405, "status", 418, "generic", 500, "validation", 400, "jpl", 502, "storage", 500).entrySet()) {
                var request = item.getKey().equals("validation")
                    ? post("/probe/validation").contentType(MediaType.APPLICATION_JSON).content("{}")
                    : get(item.getKey().equals("method") ? "/method-only" : "/probe/" + item.getKey());
                var response = mvc.perform(request.accept(accept)).andReturn().getResponse();
                assertThat(response.getStatus()).as("%s Accept: %s", item.getKey(), accept).isEqualTo(item.getValue());
                assertThat(response.getContentType()).startsWith("application/json");
                assertThat(response.getContentAsString()).contains("\"message\"");
            }
        }
        assertThat(output.getAll()).doesNotContain("Failure in @ExceptionHandler");
    }
}

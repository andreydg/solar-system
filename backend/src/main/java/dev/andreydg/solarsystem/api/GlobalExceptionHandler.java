package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.jpl.JplHorizonsException;
import dev.andreydg.solarsystem.storage.EventCatalogStorageException;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        log.warn("Bad Request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("error", "Invalid request parameter", "message", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("message", "Invalid JSON request body"));
    }

    @ExceptionHandler({org.springframework.web.HttpMediaTypeNotSupportedException.class,
        org.springframework.web.HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<Map<String, String>> handleMedia(Exception ex) {
        int status = ex instanceof org.springframework.web.HttpMediaTypeNotSupportedException ? 415 : 406;
        if (status == 406) return ResponseEntity.status(406).build();
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("message", status == 415 ? "Unsupported media type" : "Unacceptable response type"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("error", "Validation failed", "message", "Invalid body parameters"));
    }

    @ExceptionHandler(JplHorizonsException.class)
    public ResponseEntity<Map<String, String>> handleJplException(JplHorizonsException ex) {
        log.error("JPL Horizons client error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("error", "JPL Horizons service error", "message", ex.getMessage()));
    }

    @ExceptionHandler(EventCatalogStorageException.class)
    public ResponseEntity<Map<String, String>> handleStorageException(EventCatalogStorageException ex) {
        log.error("Storage error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("error", "Database storage failure", "message", "Catalog temporarily unavailable"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(404).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("message", "Resource not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405).allow(ex.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("message", "Method not allowed"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("message", ex.getReason() == null ? "Request failed" : ex.getReason()));
    }

    @ExceptionHandler({org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
        org.apache.catalina.connector.ClientAbortException.class})
    public void handleDisconnectedClient(Exception ex) {
        // The response is unusable: do not try to write an error body to the same socket.
        log.warn("Client response closed: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        // Spring selects handlers by the outer exception before looking at causes.
        // JSON writes wrap disconnects, so the catch-all must recognize the entire chain.
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof org.apache.catalina.connector.ClientAbortException
                    || cause instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException) {
                handleDisconnectedClient(ex);
                return null; // Do not serialize another body to the unusable response.
            }
        }
        log.error("Unexpected controller error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("error", "Internal Server Error", "message", "An unexpected error occurred."));
    }
}

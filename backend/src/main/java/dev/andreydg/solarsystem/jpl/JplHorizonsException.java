package dev.andreydg.solarsystem.jpl;

public class JplHorizonsException extends RuntimeException {
    private final boolean transientFailure;

    public JplHorizonsException(String message) {
        this(message, false);
    }

    public JplHorizonsException(String message, boolean transientFailure) {
        super(message);
        this.transientFailure = transientFailure;
    }

    public JplHorizonsException(String message, Throwable cause, boolean transientFailure) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    /**
     * Whether the failure is likely temporary (timeout, connection error, HTTP 429/5xx) and worth
     * retrying later, as opposed to a permanent problem with the request (bad target, 4xx, parse error).
     */
    public boolean isTransient() {
        return transientFailure;
    }
}

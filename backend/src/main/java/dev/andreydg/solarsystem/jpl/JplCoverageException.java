package dev.andreydg.solarsystem.jpl;

/** Horizons has no ephemeris for the requested dates; not a scientific rejection. */
public final class JplCoverageException extends JplHorizonsException {
    public JplCoverageException(String message) { super(message); }
}

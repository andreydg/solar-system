package dev.andreydg.solarsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "solar-system.jpl")
public record JplProperties(
    String baseUrl,
    boolean asyncValidationEnabled
) {
}

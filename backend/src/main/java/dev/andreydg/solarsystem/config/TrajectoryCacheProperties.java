package dev.andreydg.solarsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "solar-system.trajectories")
public record TrajectoryCacheProperties(
    String cacheDir,
    boolean refreshFromJpl
) {
}

package dev.andreydg.solarsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "solar-system.firestore")
public record FirestoreProperties(
    String collection,
    String databaseId,
    String emulatorHost
) {
}

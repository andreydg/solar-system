package dev.andreydg.solarsystem.api;

import jakarta.validation.constraints.NotBlank;

public record GenerateEventsRequest(
    @NotBlank String type,
    @NotBlank String bodyA,
    @NotBlank String bodyB,
    @NotBlank String from,
    @NotBlank String to
) {
}

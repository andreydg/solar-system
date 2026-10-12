package dev.andreydg.solarsystem.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateEventsRequest(
    @NotBlank @Size(max = 32) String type,
    @NotBlank @Size(max = 16) String bodyA,
    @NotBlank @Size(max = 16) String bodyB,
    @NotBlank @Size(max = 40) String from,
    @NotBlank @Size(max = 40) String to
) {
}

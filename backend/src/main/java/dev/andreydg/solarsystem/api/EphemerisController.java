package dev.andreydg.solarsystem.api;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.ephemeris.CompositeEphemerisProvider;
import dev.andreydg.solarsystem.ephemeris.JplEphemerisProvider.TrajectorySample;
import dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EphemerisController {
    private final CompositeEphemerisProvider ephemerisProvider;
    private final SmallBodyTrajectoryService trajectoryService;

    public EphemerisController(
        CompositeEphemerisProvider ephemerisProvider,
        SmallBodyTrajectoryService trajectoryService
    ) {
        this.ephemerisProvider = ephemerisProvider;
        this.trajectoryService = trajectoryService;
    }

    @GetMapping("/api/ephemeris/position")
    public PositionDto position(@RequestParam String body, @RequestParam String time) {
        BodyId bodyId = BodyId.fromApiValue(body);
        Instant timeUtc = Instant.parse(time);
        Vector3Au position = bodyId.isSmallBody()
            ? trajectoryService.interpolate(bodyId, timeUtc).orElseGet(() -> ephemerisProvider.heliocentricPosition(bodyId, timeUtc))
            : ephemerisProvider.heliocentricPosition(bodyId, timeUtc);

        return new PositionDto(
            bodyId.apiValue(),
            position.x(),
            position.y(),
            position.z(),
            ephemerisProvider.sourceFor(bodyId)
        );
    }

    @GetMapping("/api/ephemeris/positions")
    public List<PositionDto> positions(@RequestParam List<String> bodies, @RequestParam String time) {
        Instant timeUtc = Instant.parse(time);

        return bodies.stream()
            .map(BodyId::fromApiValue)
            .map(bodyId -> {
                if (bodyId.isSmallBody()) {
                    return trajectoryService.interpolate(bodyId, timeUtc)
                        .map(position -> new PositionDto(
                            bodyId.apiValue(),
                            position.x(),
                            position.y(),
                            position.z(),
                            ephemerisProvider.sourceFor(bodyId)
                        ))
                        .orElseGet(() -> livePosition(bodyId, timeUtc));
                }

                Vector3Au position = ephemerisProvider.heliocentricPosition(bodyId, timeUtc);
                return new PositionDto(
                    bodyId.apiValue(),
                    position.x(),
                    position.y(),
                    position.z(),
                    ephemerisProvider.sourceFor(bodyId)
                );
            })
            .toList();
    }

    private PositionDto livePosition(BodyId bodyId, Instant timeUtc) {
        Vector3Au position = ephemerisProvider.heliocentricPosition(bodyId, timeUtc);
        return new PositionDto(
            bodyId.apiValue(),
            position.x(),
            position.y(),
            position.z(),
            ephemerisProvider.sourceFor(bodyId)
        );
    }

    @GetMapping("/api/ephemeris/trajectory")
    public List<TrajectoryPointDto> trajectory(@RequestParam String body) {
        BodyId bodyId = BodyId.fromApiValue(body);
        return trajectoryService.getTrajectory(bodyId).stream()
            .map(TrajectoryPointDto::from)
            .toList();
    }

    public record PositionDto(
        String body,
        double x,
        double y,
        double z,
        String source
    ) {
    }

    public record TrajectoryPointDto(String timeUtc, double x, double y, double z) {
        private static TrajectoryPointDto from(TrajectorySample sample) {
            return new TrajectoryPointDto(
                sample.timeUtc().toString(),
                sample.position().x(),
                sample.position().y(),
                sample.position().z()
            );
        }
    }
}

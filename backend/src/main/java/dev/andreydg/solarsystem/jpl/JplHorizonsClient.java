package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class JplHorizonsClient {
    private static final DateTimeFormatter HORIZONS_TIME_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneOffset.UTC);
    private static final Map<BodyId, String> COMMAND_BY_BODY = Map.of(
        BodyId.MERCURY, "199",
        BodyId.VENUS, "299",
        BodyId.EARTH, "399",
        BodyId.MARS, "499",
        BodyId.JUPITER, "599",
        BodyId.SATURN, "699",
        BodyId.URANUS, "799",
        BodyId.NEPTUNE, "899"
    );

    private final JplProperties properties;
    private final RestClient restClient;

    public JplHorizonsClient(JplProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    public JplVector vector(BodyId body, Instant timeUtc) {
        Instant stop = timeUtc.plusSeconds(60);
        URI uri = UriComponentsBuilder.fromUriString(properties.baseUrl())
            .queryParam("format", "text")
            .queryParam("COMMAND", quote(COMMAND_BY_BODY.get(body)))
            .queryParam("CENTER", quote("500@10"))
            .queryParam("EPHEM_TYPE", quote("VECTORS"))
            .queryParam("VEC_TABLE", quote("2"))
            .queryParam("OUT_UNITS", quote("AU-D"))
            .queryParam("CSV_FORMAT", quote("YES"))
            .queryParam("OBJ_DATA", quote("NO"))
            .queryParam("START_TIME", quote(HORIZONS_TIME_FORMAT.format(timeUtc)))
            .queryParam("STOP_TIME", quote(HORIZONS_TIME_FORMAT.format(stop)))
            .queryParam("STEP_SIZE", quote("1m"))
            .build()
            .toUri();

        String response;

        try {
            response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);
        } catch (HttpStatusCodeException exception) {
            throw new JplHorizonsException(
                "JPL Horizons returned HTTP %s for %s at %s".formatted(
                    exception.getStatusCode(),
                    body.apiValue(),
                    timeUtc
                )
            );
        }

        if (response == null || response.isBlank()) {
            throw new JplHorizonsException("JPL Horizons returned an empty response");
        }

        return parseVector(body, timeUtc, response);
    }

    private static JplVector parseVector(BodyId body, Instant timeUtc, String response) {
        boolean inData = false;

        for (String line : response.lines().toList()) {
            String trimmed = line.trim();

            if ("$$SOE".equals(trimmed)) {
                inData = true;
                continue;
            }

            if ("$$EOE".equals(trimmed)) {
                break;
            }

            if (inData && !trimmed.isBlank()) {
                String[] columns = trimmed.split(",");
                if (columns.length < 5) {
                    throw new JplHorizonsException("Unable to parse JPL vector row: " + trimmed);
                }

                double x = Double.parseDouble(columns[2].trim());
                double y = Double.parseDouble(columns[3].trim());
                double z = Double.parseDouble(columns[4].trim());

                return new JplVector(
                    body,
                    timeUtc,
                    new Vector3Au(x, y, z),
                    columns[1].trim(),
                    trimmed
                );
            }
        }

        throw new JplHorizonsException("JPL Horizons response did not contain vector data");
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }
}

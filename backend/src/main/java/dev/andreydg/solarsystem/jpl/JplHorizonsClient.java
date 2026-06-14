package dev.andreydg.solarsystem.jpl;

import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.config.JplProperties;
import dev.andreydg.solarsystem.ephemeris.Vector3Au;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Component
public class JplHorizonsClient {
    private static final DateTimeFormatter HORIZONS_TIME_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HORIZONS_ROW_TIME_FORMAT = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("yyyy-MMM-dd HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .toFormatter(Locale.ENGLISH)
        .withZone(ZoneOffset.UTC);
    private static final Map<BodyId, String> COMMAND_BY_BODY = Map.ofEntries(
        Map.entry(BodyId.MERCURY, BodyId.MERCURY.horizonsCommand()),
        Map.entry(BodyId.VENUS, BodyId.VENUS.horizonsCommand()),
        Map.entry(BodyId.EARTH, BodyId.EARTH.horizonsCommand()),
        Map.entry(BodyId.MARS, BodyId.MARS.horizonsCommand()),
        Map.entry(BodyId.JUPITER, BodyId.JUPITER.horizonsCommand()),
        Map.entry(BodyId.SATURN, BodyId.SATURN.horizonsCommand()),
        Map.entry(BodyId.URANUS, BodyId.URANUS.horizonsCommand()),
        Map.entry(BodyId.NEPTUNE, BodyId.NEPTUNE.horizonsCommand()),
        Map.entry(BodyId.CERES, BodyId.CERES.horizonsCommand()),
        Map.entry(BodyId.VESTA, BodyId.VESTA.horizonsCommand()),
        Map.entry(BodyId.ENCKE, BodyId.ENCKE.horizonsCommand()),
        Map.entry(BodyId.HALLEY, BodyId.HALLEY.horizonsCommand())
    );

    private final JplProperties properties;
    private final RestClient restClient;

    public JplHorizonsClient(JplProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    public JplVector vector(BodyId body, Instant timeUtc) {
        List<JplVector> vectors = vectors(body, timeUtc, timeUtc.plusSeconds(60), "1m");
        if (vectors.isEmpty()) {
            throw new JplHorizonsException("JPL Horizons response did not contain vector data");
        }

        return vectors.getFirst();
    }

    public List<JplVector> vectors(BodyId body, Instant startUtc, Instant stopUtc, String stepSize) {
        String command = COMMAND_BY_BODY.get(body);
        if (command == null) {
            throw new JplHorizonsException("No JPL Horizons command configured for " + body.apiValue());
        }

        URI uri = buildUri(
            "format", "text",
            "COMMAND", quote(command),
            "CENTER", quote("500@10"),
            "EPHEM_TYPE", quote("VECTORS"),
            "VEC_TABLE", quote("2"),
            "OUT_UNITS", quote("AU-D"),
            "CSV_FORMAT", quote("YES"),
            "OBJ_DATA", quote("NO"),
            "START_TIME", quote(HORIZONS_TIME_FORMAT.format(startUtc)),
            "STOP_TIME", quote(HORIZONS_TIME_FORMAT.format(stopUtc)),
            "STEP_SIZE", quote(stepSize)
        );

        String response = fetchResponse(body, startUtc, uri);
        return parseVectors(body, response);
    }

    private String fetchResponse(BodyId body, Instant contextTime, URI uri) {
        try {
            String response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);

            if (response == null || response.isBlank()) {
                throw new JplHorizonsException("JPL Horizons returned an empty response");
            }

            validateResponse(response);
            return response;
        } catch (HttpStatusCodeException exception) {
            throw new JplHorizonsException(
                "JPL Horizons returned HTTP %s for %s at %s: %s".formatted(
                    exception.getStatusCode(),
                    body.apiValue(),
                    contextTime,
                    exception.getResponseBodyAsString().lines()
                        .filter(line -> !line.isBlank())
                        .reduce((first, second) -> second)
                        .orElse("no response body")
                )
            );
        }
    }

    private static void validateResponse(String response) {
        if (response.contains("No ephemeris")) {
            throw new JplHorizonsException(extractHorizonsMessage(response));
        }

        if (response.contains("Matching small-bodies:") || response.contains("To SELECT, enter record")) {
            throw new JplHorizonsException(
                "Ambiguous JPL Horizons target; configure a specific orbit record number"
            );
        }

        if (response.contains("Missing operator")) {
            throw new JplHorizonsException(extractHorizonsMessage(response));
        }
    }

    private static String extractHorizonsMessage(String response) {
        return response.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !line.startsWith("API "))
            .filter(line -> !line.startsWith("*"))
            .reduce((first, second) -> second)
            .orElse("JPL Horizons request failed");
    }

    private static List<JplVector> parseVectors(BodyId body, String response) {
        List<JplVector> vectors = new ArrayList<>();
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
                vectors.add(parseVectorRow(body, trimmed));
            }
        }

        return vectors;
    }

    private static JplVector parseVectorRow(BodyId body, String row) {
        String[] columns = row.split(",");
        if (columns.length < 5) {
            throw new JplHorizonsException("Unable to parse JPL vector row: " + row);
        }

        double x = Double.parseDouble(columns[2].trim());
        double y = Double.parseDouble(columns[3].trim());
        double z = Double.parseDouble(columns[4].trim());

        return new JplVector(
            body,
            parseRowTime(columns[1]),
            new Vector3Au(x, y, z),
            columns[1].trim(),
            row
        );
    }

    private static Instant parseRowTime(String rawColumn) {
        String trimmed = rawColumn.trim();
        if (trimmed.startsWith("A.D.")) {
            trimmed = trimmed.substring(4).trim();
        }

        return Instant.from(HORIZONS_ROW_TIME_FORMAT.parse(trimmed));
    }

    private URI buildUri(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }

        Map<String, String> ordered = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            ordered.put(keyValues[index], keyValues[index + 1]);
        }

        String query = ordered.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + urlEncode(entry.getValue()))
            .collect(Collectors.joining("&"));

        return URI.create(properties.baseUrl() + "?" + query);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }
}

package dev.andreydg.solarsystem.api;

import com.google.common.net.InetAddresses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded per-client quotas plus independent instance concurrency backstops. */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiGuardFilter extends OncePerRequestFilter {
    private final String adminToken;
    private final Semaphore work = new Semaphore(2);
    private final Semaphore reads = new Semaphore(32);
    private final Semaphore jpl = new Semaphore(8);
    private final Semaphore trajectories = new Semaphore(16);
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private final int[] overflow = new int[3];
    private final int[] malformed = new int[3];
    private final Clock clock;
    private final int trustedProxyHops;
    private final Map<String, int[]> clients = new HashMap<>();
    private long window;
    private int requests;

    @org.springframework.beans.factory.annotation.Autowired
    public ApiGuardFilter(@Value("${solar-system.admin-token:}") String adminToken,
        @Value("${solar-system.trusted-proxy-hops:-1}") int trustedProxyHops,
        @Value("${K_SERVICE:}") String cloudRunService) {
        this(adminToken, Clock.systemUTC(), proxyHops(trustedProxyHops, cloudRunService));
    }

    ApiGuardFilter(String adminToken) { this(adminToken, Clock.systemUTC(), 0); }
    ApiGuardFilter(String adminToken, Clock clock) { this(adminToken, clock, 0); }
    ApiGuardFilter(String adminToken, Clock clock, int trustedProxyHops) {
        String normalized = adminToken.trim();
        this.adminToken = normalized.length() >= 32 ? normalized : "";
        this.clock = clock;
        if (trustedProxyHops < 0 || trustedProxyHops > 10) throw new IllegalArgumentException("Invalid proxy hop count");
        this.trustedProxyHops = trustedProxyHops;
    }

    static int proxyHops(int configured, String cloudRunService) {
        if (configured >= 0) return configured;
        if (!cloudRunService.isBlank()) throw new IllegalArgumentException(
            "Set SOLAR_SYSTEM_TRUSTED_PROXY_HOPS for the deployed ingress (1 for direct Cloud Run)");
        return 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        boolean administrative = path.startsWith("/api/validation/") || path.startsWith("/api/admin/");
        if (administrative) {
            if (adminToken.isEmpty()) {
                reject(response, 503, "Administrative API is disabled");
                return;
            }
            String supplied = request.getHeader("Authorization");
            if (supplied == null || supplied.length() > 512 || !supplied.regionMatches(true, 0, "Bearer ", 0, 7)
                || !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
                    supplied.substring(7).getBytes(StandardCharsets.UTF_8))) {
                response.setHeader("WWW-Authenticate", "Bearer");
                reject(response, 401, "Authentication required");
                return;
            }
        }
        if (request.getContentLengthLong() > 4096) {
            reject(response, 413, "Request body is too large");
            return;
        }
        byte[] body = new byte[0];
        if (java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
            // Read at most limit+1, including chunked/HTTP2 bodies with no Content-Length.
            body = request.getInputStream().readNBytes(4097);
            if (body.length > 4096) {
                reject(response, 413, "Request body is too large");
                return;
            }
            request = replayable(request, body);
        }
        if (path.equals("/api/health") || !path.startsWith("/api/events")
            && !path.startsWith("/api/ephemeris/") && !administrative) {
            chain.doFilter(request, response);
            return;
        }
        // Durable distributed leases, not visitor quotas, bound the authenticated worker.
        if (path.equals("/api/admin/validation/drain")) {
            chain.doFilter(request, response);
            return;
        }
        boolean expensive = path.equals("/api/events/generate");
        boolean jplBound = administrative || path.startsWith("/api/ephemeris/position")
            || expensive && smallBodyGeneration(body);
        Semaphore permits = jplBound ? jpl : expensive ? work
            : path.equals("/api/ephemeris/trajectory") ? trajectories : reads;
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            reject(response, 429, "Work capacity exceeded; retry later");
            return;
        }
        try {
            if (!admit(clientKey(request), expensive ? 0 : jplBound ? 1 : 2)) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "Request quota exceeded; retry later");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private static boolean smallBodyGeneration(byte[] body) {
        try {
            var json = JSON.readTree(body);
            for (String field : new String[] {"bodyA", "bodyB"}) {
                if (json != null && json.has(field) && java.util.Set.of("ceres", "vesta", "encke", "halley")
                    .contains(json.get(field).asText())) return true;
            }
        } catch (RuntimeException ignored) { /* MVC reports malformed JSON; never perform work here. */ }
        return false;
    }

    private String clientKey(HttpServletRequest request) {
        if (trustedProxyHops == 0) return addressKey(request.getRemoteAddr());
        var values = request.getHeaders("X-Forwarded-For");
        var forwarded = new StringBuilder();
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            if (forwarded.length() + value.length() + 1 > 2048) return "!malformed";
            if (!forwarded.isEmpty()) forwarded.append(',');
            forwarded.append(value);
        }
        String[] hops = forwarded.toString().split(",", -1);
        if (hops.length < trustedProxyHops) return "!malformed";
        return addressKey(hops[hops.length - trustedProxyHops].trim());
    }

    private static String addressKey(String value) {
        if (!InetAddresses.isInetAddress(value)) return "!malformed";
        var address = InetAddresses.forString(value);
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            java.util.Arrays.fill(bytes, 8, 16, (byte) 0);
            try { address = java.net.InetAddress.getByAddress(bytes); }
            catch (java.net.UnknownHostException impossible) { throw new AssertionError(impossible); }
        }
        return InetAddresses.toAddrString(address);
    }

    private synchronized boolean admit(String client, int pool) {
        long current = clock.millis() / 60_000;
        if (current != window) {
            window = current;
            requests = 0;
            clients.clear();
            java.util.Arrays.fill(overflow, 0);
            java.util.Arrays.fill(malformed, 0);
        }
        int[] counts = client.equals("!malformed") ? malformed : clients.get(client);
        // Never evict existing clients (which would let a client reset its quota).
        if (counts == null && clients.size() < 4096) {
            counts = new int[3];
            clients.put(client, counts);
        }
        if (counts == null) counts = overflow;
        int limit = pool == 0 ? 60 : pool == 1 ? 120 : 600;
        if (counts == malformed || counts == overflow) limit /= 10;
        if (requests >= 6000 || counts[pool] >= limit) return false;
        requests++;
        counts[pool]++;
        return true;
    }

    private static HttpServletRequest replayable(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API"); }
                };
            }
            @Override public java.io.BufferedReader getReader() {
                return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}

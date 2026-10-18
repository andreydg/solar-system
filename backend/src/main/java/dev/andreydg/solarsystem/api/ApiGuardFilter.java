package dev.andreydg.solarsystem.api;

import com.google.common.net.InetAddresses;
import dev.andreydg.solarsystem.catalog.BodyId;
import dev.andreydg.solarsystem.ephemeris.SmallBodyTrajectoryService;
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
    private final Semaphore warmTrajectories = new Semaphore(16);
    // Entries exist only while a global trajectory permit is held; both maps are bounded by 32.
    private final Map<String, Integer> responseClients = new HashMap<>();
    private final Map<String, Integer> responseSubscribers = new HashMap<>();
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private final int[] overflow = new int[3];
    private final int[] malformed = new int[3];
    private final java.util.function.Predicate<BodyId> trajectoryCached;
    private final Map<String, Integer> subscribers = new HashMap<>();
    private static final int MAX_CLIENTS = 8192; // Larger than the 6000 admitted-request backstop.
    private static final int SUBSCRIBER_LIMIT = 4800;
    private final Clock clock;
    private final int trustedProxyHops;
    private final Map<String, int[]> clients = new HashMap<>();
    private long window;
    private int requests;

    @org.springframework.beans.factory.annotation.Autowired
    public ApiGuardFilter(@Value("${solar-system.admin-token:}") String adminToken,
        @Value("${solar-system.trusted-proxy-hops:-1}") int trustedProxyHops,
        @Value("${K_SERVICE:}") String cloudRunService,
        org.springframework.beans.factory.ObjectProvider<SmallBodyTrajectoryService> trajectoryService) {
        this(adminToken, Clock.systemUTC(), proxyHops(trustedProxyHops, cloudRunService), body -> trajectoryService.getObject().isCached(body));
    }

    public ApiGuardFilter(String adminToken, int hops, String service) {
        this(adminToken, Clock.systemUTC(), proxyHops(hops, service));
    }
    ApiGuardFilter(String adminToken) { this(adminToken, Clock.systemUTC(), 0); }
    ApiGuardFilter(String adminToken, Clock clock) { this(adminToken, clock, 0); }
    ApiGuardFilter(String adminToken, Clock clock, int trustedProxyHops) {
        this(adminToken, clock, trustedProxyHops, body -> false);
    }
    ApiGuardFilter(String adminToken, Clock clock, int trustedProxyHops, java.util.function.Predicate<BodyId> trajectoryCached) {
        this.trajectoryCached = trajectoryCached;
        String normalized = adminToken.trim();
        this.adminToken = normalized.length() >= 32 ? normalized : "";
        this.clock = clock;
        if (trustedProxyHops < 0 || trustedProxyHops > 10) throw new IllegalArgumentException("Invalid proxy hop count");
        this.trustedProxyHops = trustedProxyHops;
    }

    static int proxyHops(int configured, String cloudRunService) {
        if (!cloudRunService.isBlank() && configured <= 0) throw new IllegalArgumentException(
            "Set SOLAR_SYSTEM_TRUSTED_PROXY_HOPS for the deployed ingress (1 for direct Cloud Run)");
        return configured < 0 ? 0 : configured;
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
        String client = clientKey(request);
        boolean warm = path.equals("/api/ephemeris/trajectory") && warmTrajectory(request);
        Semaphore permits = jplBound ? jpl : expensive ? work
            : path.equals("/api/ephemeris/trajectory") ? (warm ? warmTrajectories : trajectories) : reads;
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            reject(response, 429, "Work capacity exceeded; retry later");
            return;
        }
        boolean responseAdmitted = false;
        try {
            if (path.equals("/api/ephemeris/trajectory") && !(responseAdmitted = acquireResponse(client))) {
                response.setHeader("Retry-After", "1");
                reject(response, 429, "Client response capacity exceeded; retry later");
                return;
            }
            if (!admit(client, expensive ? 0 : jplBound ? 1 : 2)) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "Request quota exceeded; retry later");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            if (responseAdmitted) releaseResponse(client);
            permits.release();
        }
    }

    private static boolean smallBodyGeneration(byte[] body) {
        try {
            var json = JSON.readTree(body);
            for (String field : new String[] {"bodyA", "bodyB"}) {
                if (json != null && json.has(field)) {
                    try { if (BodyId.fromApiValue(json.get(field).asText()).isSmallBody()) return true; }
                    catch (IllegalArgumentException ignored) { /* MVC validates the identifier. */ }
                }
            }
        } catch (RuntimeException ignored) { /* MVC reports malformed JSON; never perform work here. */ }
        return false;
    }

    private boolean warmTrajectory(HttpServletRequest request) {
        String body = request.getParameter("body");
        if (body == null) return false;
        try { return trajectoryCached.test(BodyId.fromApiValue(body)); }
        catch (IllegalArgumentException invalid) { return false; }
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
        if (value == null || value.contains("%") || !InetAddresses.isInetAddress(value)) return "!malformed";
        var address = InetAddresses.forString(value);
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            java.util.Arrays.fill(bytes, 8, 16, (byte) 0);
            try { address = java.net.InetAddress.getByAddress(bytes); }
            catch (java.net.UnknownHostException impossible) { throw new AssertionError(impossible); }
        }
        return InetAddresses.toAddrString(address);
    }

    private static String subscriberKey(String client) {
        if (!client.contains(":")) return null;
        byte[] bytes = InetAddresses.forString(client).getAddress();
        return java.util.HexFormat.of().formatHex(bytes, 0, 7); // /56, while client keys retain /64
    }

    private synchronized boolean acquireResponse(String client) {
        String subscriber = subscriberKey(client);
        if (responseClients.getOrDefault(client, 0) >= (client.equals("!malformed") ? 1 : 4)
            || subscriber != null && responseSubscribers.getOrDefault(subscriber, 0) >= 8) return false;
        responseClients.merge(client, 1, Integer::sum);
        if (subscriber != null) responseSubscribers.merge(subscriber, 1, Integer::sum);
        return true;
    }

    private synchronized void releaseResponse(String client) {
        responseClients.computeIfPresent(client, (key, count) -> count == 1 ? null : count - 1);
        String subscriber = subscriberKey(client);
        if (subscriber != null) responseSubscribers.computeIfPresent(subscriber, (key, count) -> count == 1 ? null : count - 1);
    }

    private synchronized boolean admit(String client, int pool) {
        long current = clock.millis() / 60_000;
        if (current != window) {
            window = current;
            requests = 0;
            clients.clear();
            subscribers.clear();
            java.util.Arrays.fill(overflow, 0);
            java.util.Arrays.fill(malformed, 0);
        }
        if (requests >= 6000) return false;
        String subscriber = subscriberKey(client);
        if (subscriber != null && subscribers.getOrDefault(subscriber, 0) >= SUBSCRIBER_LIMIT) return false;
        int[] counts = client.equals("!malformed") ? malformed : clients.get(client);
        // Never evict existing clients (which would let a client reset its quota).
        if (counts == null && clients.size() < MAX_CLIENTS) {
            counts = new int[3];
            clients.put(client, counts);
        }
        if (counts == null) counts = overflow;
        int limit = pool == 0 ? 60 : pool == 1 ? 120 : 600;
        if (counts == malformed || counts == overflow) limit /= 10;
        if (requests >= 6000 || counts[pool] >= limit) return false;
        requests++;
        counts[pool]++;
        if (subscriber != null) subscribers.merge(subscriber, 1, Integer::sum);
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

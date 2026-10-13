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
public class ApiGuardFilter extends OncePerRequestFilter {
    private final String adminToken;
    private final Semaphore work = new Semaphore(2);
    private final Semaphore reads = new Semaphore(32);
    private final Semaphore jpl = new Semaphore(8);
    private final Clock clock;
    private final int trustedProxyHops;
    private final Map<String, int[]> clients = new HashMap<>();
    private long window;
    private int requests;

    @org.springframework.beans.factory.annotation.Autowired
    public ApiGuardFilter(@Value("${solar-system.admin-token:}") String adminToken,
        @Value("${solar-system.trusted-proxy-hops:-1}") int trustedProxyHops) {
        this(adminToken, Clock.systemUTC(), trustedProxyHops < 0
            ? (System.getenv("K_SERVICE") == null ? 0 : 1) : trustedProxyHops);
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
        if (java.util.Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
            // Read at most limit+1, including chunked/HTTP2 bodies with no Content-Length.
            byte[] body = request.getInputStream().readNBytes(4097);
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
        boolean expensive = path.equals("/api/events/generate");
        boolean jplBound = administrative || path.startsWith("/api/ephemeris/position");
        Semaphore permits = expensive ? work : jplBound ? jpl : reads;
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

    private String clientKey(HttpServletRequest request) {
        if (trustedProxyHops > 0) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && forwarded.length() < 2048) {
                String[] hops = forwarded.split(",", -1);
                if (hops.length >= trustedProxyHops) {
                    String candidate = hops[hops.length - trustedProxyHops].trim();
                    if (InetAddresses.isInetAddress(candidate)) return InetAddresses.toAddrString(InetAddresses.forString(candidate));
                }
            }
        }
        return request.getRemoteAddr();
    }

    private synchronized boolean admit(String client, int pool) {
        long current = clock.millis() / 60_000;
        if (current != window) {
            window = current;
            requests = 0;
            clients.clear();
        }
        int[] counts = clients.get(client);
        // Never evict existing clients (which would let a client reset its quota).
        if (counts == null && clients.size() < 4096) {
            counts = new int[3];
            clients.put(client, counts);
        }
        int limit = pool == 0 ? 60 : pool == 1 ? 120 : 600;
        if (requests >= 6000 || counts != null && counts[pool] >= limit) return false;
        requests++;
        if (counts != null) counts[pool]++;
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

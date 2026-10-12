package dev.andreydg.solarsystem.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A bounded per-instance safety net, not a substitute for deployment-wide edge quotas. */
@Component
public class ApiGuardFilter extends OncePerRequestFilter {
    private final String adminToken;
    private final Semaphore work = new Semaphore(2);
    private final Clock clock;
    private long window;
    private int requests;

    @org.springframework.beans.factory.annotation.Autowired
    public ApiGuardFilter(@Value("${solar-system.admin-token:}") String adminToken) {
        this(adminToken, Clock.systemUTC());
    }

    ApiGuardFilter(String adminToken, Clock clock) {
        if (!adminToken.isEmpty() && adminToken.length() < 32) {
            throw new IllegalArgumentException("SOLAR_SYSTEM_ADMIN_TOKEN must contain at least 32 characters");
        }
        this.adminToken = adminToken;
        this.clock = clock;
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
        if (request.getContentLengthLong() > 4096) {
            reject(response, 413, "Request body is too large");
            return;
        }
        if (path.startsWith("/api/validation/") || path.startsWith("/api/admin/")) {
            if (adminToken.isEmpty()) {
                reject(response, 503, "Administrative API is disabled");
                return;
            }
            String supplied = request.getHeader("Authorization");
            if (supplied == null || supplied.length() > 512 || !MessageDigest.isEqual(
                ("Bearer " + adminToken).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setHeader("WWW-Authenticate", "Bearer");
                reject(response, 401, "Authentication required");
                return;
            }
        }
        boolean expensive = path.startsWith("/api/events") || path.startsWith("/api/ephemeris/")
            || path.startsWith("/api/validation/") || path.startsWith("/api/admin/");
        if (!expensive) {
            chain.doFilter(request, response);
            return;
        }
        if (!admit() || !work.tryAcquire()) {
            response.setHeader("Retry-After", "60");
            reject(response, 429, "Work capacity exceeded; retry later");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            work.release();
        }
    }

    private synchronized boolean admit() {
        long current = clock.millis() / 60_000;
        if (current != window) {
            window = current;
            requests = 0;
        }
        return ++requests <= 60;
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}

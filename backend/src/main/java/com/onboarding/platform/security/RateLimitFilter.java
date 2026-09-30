package com.onboarding.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limits on the endpoints worth abusing:
 *   - login/register, per client IP: slows password guessing and mass sign-ups;
 *   - document and selfie uploads, per signed-in user: each one is a multi-second AI run.
 *
 * In-memory, so limits are per backend instance -- fine for a single instance. Behind a
 * reverse proxy, set server.forward-headers-strategy=native so the client IP is the real one.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String name, int limit, Duration window) {}

    private static final class Window {
        long start;
        int count;
    }

    private final Rule authRule;
    private final Rule uploadRule;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.rate-limit.auth-per-minute:10}") int authPerMinute,
                           @Value("${app.rate-limit.uploads-per-hour:30}") int uploadsPerHour) {
        this.authRule = new Rule("auth", authPerMinute, Duration.ofMinutes(1));
        this.uploadRule = new Rule("upload", uploadsPerHour, Duration.ofHours(1));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Rule rule = null;
        String subject = null;
        if ("POST".equals(request.getMethod())) {
            if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
                rule = authRule;
                subject = request.getRemoteAddr();
            } else if (path.startsWith("/api/onboarding/sessions/")
                    && (path.endsWith("/document") || path.endsWith("/selfie"))) {
                rule = uploadRule;
                subject = currentUser();
            }
        }
        if (rule != null && subject != null) {
            long retryAfter = consume(rule, subject);
            if (retryAfter > 0) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                response.getWriter().write("Too many attempts. Please wait " + humanize(retryAfter) + " and try again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Counts one request; returns 0 if allowed, else the seconds until the window resets. */
    private long consume(Rule rule, String subject) {
        long now = System.currentTimeMillis();
        long windowMs = rule.window().toMillis();
        Window window = windows.computeIfAbsent(rule.name() + ":" + subject, k -> new Window());
        synchronized (window) {
            if (now - window.start >= windowMs) {
                window.start = now;
                window.count = 0;
            }
            if (window.count >= rule.limit()) {
                return Math.max(1, (window.start + windowMs - now + 999) / 1000);
            }
            window.count++;
        }
        if (windows.size() > 10_000) {
            // Keep memory bounded: forget windows that have expired.
            windows.entrySet().removeIf(e -> now - e.getValue().start >= Duration.ofHours(1).toMillis());
        }
        return 0;
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken ? null : auth.getName();
    }

    private static String humanize(long seconds) {
        return seconds < 90 ? seconds + " seconds" : (seconds + 59) / 60 + " minutes";
    }
}

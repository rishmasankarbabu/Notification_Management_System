package com.cup.notificationboard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 120;
    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private static final long WINDOW_MILLIS = 60_000;

    private final ConcurrentHashMap<String, RateWindow> clients = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String client = request.getRemoteAddr();

        if (!clients.containsKey(client) && clients.size() >= MAX_TRACKED_CLIENTS) {
            clients.entrySet().removeIf(entry -> now - entry.getValue().windowStart() >= WINDOW_MILLIS);
            if (clients.size() >= MAX_TRACKED_CLIENTS) {
                reject(response);
                return;
            }
        }

        AtomicBoolean allowed = new AtomicBoolean();
        clients.compute(client, (address, current) -> {
            if (current == null || now - current.windowStart() >= WINDOW_MILLIS) {
                allowed.set(true);
                return new RateWindow(now, 1);
            }
            if (current.requests() >= MAX_REQUESTS_PER_MINUTE) {
                return current;
            }
            allowed.set(true);
            return new RateWindow(current.windowStart(), current.requests() + 1);
        });

        if (!allowed.get()) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/problem+json");
        response.setHeader("Retry-After", "60");
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\","
                        + "\"status\":429,\"detail\":\"Request rate limit exceeded\"}"
        );
    }

    private record RateWindow(long windowStart, int requests) {
    }
}

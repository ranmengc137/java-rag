package com.randy.rag.config;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int maxRequestsPerMinute;
    private final Map<String, RequestWindow> counters = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${ratelimit.per-minute:60}") int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (maxRequestsPerMinute <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = request.getRemoteAddr();
        long now = Instant.now().toEpochMilli();
        RequestWindow window = counters.computeIfAbsent(key, k -> new RequestWindow(now));

        synchronized (window) {
            if (now - window.windowStartMs >= 60_000) {
                window.windowStartMs = now;
                window.count.set(0);
            }
            if (window.count.incrementAndGet() > maxRequestsPerMinute) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.getWriter().write("Rate limit exceeded");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static class RequestWindow {
        private long windowStartMs;
        private final AtomicInteger count = new AtomicInteger(0);

        private RequestWindow(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}

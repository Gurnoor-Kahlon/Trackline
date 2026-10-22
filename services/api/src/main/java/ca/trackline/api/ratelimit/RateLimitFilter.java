package ca.trackline.api.ratelimit;

import java.io.IOException;

import ca.trackline.api.ratelimit.RateLimitProperties.Limit;
import ca.trackline.api.ratelimit.TokenBuckets.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Refuses a caller that is making requests faster than a person could.
 *
 * <p>Limited by the address the connection came from, which behind a load balancer is the visitor's only because the production
 * profile reads it from {@code X-Forwarded-For} (see docs/streaming.md). There are three budgets, because the calls cost different
 * amounts: reads of transit data, search (a trigram query across three tables) and writes. A refusal is a {@code 429} problem
 * document with a {@code Retry-After}, the same shape as every other error.
 *
 * <p>What it leaves alone, and why:
 * <ul>
 *   <li>The live stream, which has its own caps on open connections per address and in total.
 *   <li>Sign-in and the other account calls. The web application relays them, so they all arrive from one address, and a limit
 *       per address would throttle every visitor for one visitor's behaviour. Guessing a password is slowed by the limit on failed
 *       sign-ins per account instead.
 *   <li>The health endpoint, because a load balancer asks it far more often than any visitor, and its being refused would
 *       take a healthy instance out of service.
 *   <li>A browser's preflight, which carries no data and is answered before the real request.
 * </ul>
 *
 * <p>Runs after the security chain, so the answer to a refused request still carries the CORS headers a browser needs to be
 * allowed to read it; without them a page would see a failed network request, not a {@code 429} it could wait out.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private static final String API = "/api/";

    private final RateLimitProperties properties;
    private final TokenBuckets buckets;
    private final HandlerExceptionResolver errors;

    public RateLimitFilter(RateLimitProperties properties, TokenBuckets buckets, HandlerExceptionResolver errors) {
        this.properties = properties;
        this.buckets = buckets;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !properties.enabled()
                || !path.startsWith(API)
                || "OPTIONS".equals(request.getMethod())
                || path.startsWith("/api/v1/stream/")
                || path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String kind;
        Limit limit;
        if (request.getRequestURI().startsWith("/api/v1/search")) {
            kind = "search";
            limit = properties.search();
        } else if (isWrite(request)) {
            kind = "write";
            limit = properties.writes();
        } else {
            kind = "read";
            limit = properties.reads();
        }

        Decision decision = buckets.take(request.getRemoteAddr() + "|" + kind, limit);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        errors.resolveException(request, response, null, new RateLimitedException(decision.retryAfter()));
    }

    private static boolean isWrite(HttpServletRequest request) {
        String method = request.getMethod();
        return "PUT".equals(method) || "POST".equals(method) || "DELETE".equals(method);
    }
}

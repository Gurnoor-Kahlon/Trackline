package ca.trackline.api.auth;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * What the filter chain answers when a request is turned away before any controller runs.
 *
 * <p>The same problem-detail shape every other error uses, so a client has one error format to handle, and the same
 * rule about content: the body says that a token is needed, never why a particular token was refused. Whether a
 * token was expired, forged or for another issuer is not information to give someone presenting it.
 */
@Component
public class SecurityProblems implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String TYPE_BASE = "https://trackline.ca/problems/";

    private final ObjectMapper mapper;
    private final Clock clock;

    public SecurityProblems(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** No token, or one that is not good. Bearer is named so a client knows the scheme to use. */
    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "Authentication required", "authentication-required",
                "A valid access token is required.");
    }

    /** A token that is good but not allowed here. Nothing yet needs it, since access is by who you are. */
    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Forbidden", "forbidden", "This is not available to you.");
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String title, String type,
            String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock)));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        mapper.writeValue(response.getOutputStream(), problem);
    }
}

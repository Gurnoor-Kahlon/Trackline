package ca.trackline.api.common.error;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into RFC 7807 problem details.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own
 * exceptions (unreadable body, unsupported media type, missing parameter, no
 * handler) are converted here too, so every error response has the same shape
 * regardless of where it originated.
 *
 * <p>No response includes a stack trace or exception class name: those go to the
 * logs with an {@code errorId} the client is also given, so a user can quote an
 * id without the API leaking internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Problem type URIs are documentation anchors, not dereferenced endpoints. */
    private static final String TYPE_BASE = "https://trackline.ca/problems/";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(
            ResourceNotFoundException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Resource not found");
        problem.setType(URI.create(TYPE_BASE + "resource-not-found"));
        problem.setProperty("resourceType", exception.getResourceType());
        problem.setProperty("resourceId", exception.getResourceId());
        return ResponseEntity.status(problem.getStatus()).body(decorate(problem, request.getRequestURI()));
    }

    /**
     * Last-resort handler. The message is deliberately generic: an unexpected
     * failure must not describe internals to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        String errorId = newErrorId();
        log.error("Unhandled exception errorId={} path={}", errorId, request.getRequestURI(), exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setTitle("Internal server error");
        problem.setType(URI.create(TYPE_BASE + "internal-error"));
        problem.setProperty("errorId", errorId);
        return ResponseEntity.status(problem.getStatus()).body(decorate(problem, request.getRequestURI()));
    }

    /**
     * Adds the individual field violations to the standard 400, so a client is
     * told which parameter was wrong rather than just that something was.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<Map<String, String>> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
        problem.setTitle("Invalid request");
        problem.setType(URI.create(TYPE_BASE + "validation-failed"));
        problem.setProperty("violations", violations);
        return ResponseEntity.status(status).body(decorate(problem, describe(request)));
    }

    /**
     * An unmatched path. Spring's default detail for this is "No static resource
     * ...", which describes its own resource resolver rather than the API; this
     * service exposes endpoints, so the caller is told that instead.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String path = describe(request);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "No endpoint matches %s %s.".formatted(exception.getHttpMethod(), path));
        problem.setTitle("Endpoint not found");
        problem.setType(URI.create(TYPE_BASE + "endpoint-not-found"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(decorate(problem, path));
    }

    /**
     * Applies the shared problem fields to the exceptions handled by the base
     * class (missing parameter, type mismatch, unknown path, and similar) so
     * they are not the one family of errors missing {@code timestamp}/{@code
     * instance}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> response =
                super.handleExceptionInternal(exception, body, headers, statusCode, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
                problem.setType(URI.create(TYPE_BASE + slugFor(statusCode)));
            }
            decorate(problem, describe(request));
        }
        return response;
    }

    private ProblemDetail decorate(ProblemDetail problem, String path) {
        problem.setInstance(URI.create(path));
        // Problem detail extensions are an untyped map, so their serialised form
        // would otherwise depend on global Jackson configuration. The error
        // contract is pinned here instead: an ISO-8601 instant, always.
        problem.setProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock)));
        return problem;
    }

    private static String describe(WebRequest request) {
        // WebRequest's description is prefixed with "uri=" by contract.
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : description;
    }

    private static String slugFor(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return resolved == null
                ? "http-" + statusCode.value()
                : resolved.name().toLowerCase().replace('_', '-');
    }

    private static String newErrorId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

}

package ca.trackline.api.common.error;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ca.trackline.api.auth.AccountGoneException;
import ca.trackline.api.auth.EmailAlreadyRegisteredException;
import ca.trackline.api.auth.InvalidCredentialsException;
import ca.trackline.api.auth.InvalidRefreshTokenException;
import ca.trackline.api.auth.TooManyAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
     * A request the caller got wrong in a way the validation annotations could not
     * express, such as a bounding box whose corners are the wrong way round.
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(
            InvalidRequestException exception, HttpServletRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        problem.setType(URI.create(TYPE_BASE + "validation-failed"));
        return ResponseEntity.badRequest().body(decorate(problem, request.getRequestURI()));
    }

    /**
     * The server is holding as many live streams as it will. Retry-After says when
     * trying again is reasonable, so a reconnecting client backs off instead of
     * hammering a server that has just told it no.
     */
    @ExceptionHandler(TooManyConnectionsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyConnections(
            TooManyConnectionsException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
        problem.setTitle("Too many live connections");
        problem.setType(URI.create(TYPE_BASE + "too-many-connections"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(decorate(problem, request.getRequestURI()));
    }

    /** Sign-in failed. The same answer for every reason, so it cannot be used to find out who has an account. */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return unauthorized("Sign-in failed", "invalid-credentials", exception.getMessage(), request);
    }

    /** A refresh token that cannot be used, for any reason. */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRefreshToken(
            InvalidRefreshTokenException exception, HttpServletRequest request) {
        return unauthorized("Session ended", "session-ended", exception.getMessage(), request);
    }

    /** A valid token for an account that has since been removed. */
    @ExceptionHandler(AccountGoneException.class)
    public ResponseEntity<ProblemDetail> handleAccountGone(AccountGoneException exception, HttpServletRequest request) {
        return unauthorized("Account not found", "account-gone", exception.getMessage(), request);
    }

    private ResponseEntity<ProblemDetail> unauthorized(
            String title, String type, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + type));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(decorate(problem, request.getRequestURI()));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> handleEmailAlreadyRegistered(
            EmailAlreadyRegisteredException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Email already registered");
        problem.setType(URI.create(TYPE_BASE + "email-already-registered"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(decorate(problem, request.getRequestURI()));
    }

    /** Too many failed sign-ins for one account. Retry-After says how long to wait. */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyAttempts(
            TooManyAttemptsException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
        problem.setTitle("Too many attempts");
        problem.setType(URI.create(TYPE_BASE + "too-many-attempts"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, exception.retryAfter().toSeconds())))
                .body(decorate(problem, request.getRequestURI()));
    }

    /**
     * A query or path parameter outside its allowed range.
     *
     * <p>Caps on page size, radius and result count are a protection, not an
     * internal error. Without this they reach the catch-all and surface as 500,
     * which tells a caller nothing about the parameter it got wrong and reports a
     * server fault for what is a client mistake.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {

        List<Map<String, String>> violations = exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "parameter", lastNode(violation),
                        "message", violation.getMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed.");
        problem.setTitle("Invalid request");
        problem.setType(URI.create(TYPE_BASE + "validation-failed"));
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(decorate(problem, request.getRequestURI()));
    }

    /**
     * The property path is "method.parameter", and only the parameter name means
     * anything to a caller.
     */
    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    /**
     * A write failing because the other end has gone.
     *
     * <p>A live stream is opened by writing to the client, and a browser tab can be
     * closed in the middle of it. The failed write surfaces here as a bare I/O error,
     * and without this it would be logged as an unhandled exception and then fail a
     * second time trying to write a JSON error into a response that is already an
     * event stream. Someone leaving is not an error, so it is dropped quietly.
     *
     * <p>Only for event-stream responses. An I/O error anywhere else is still a
     * server fault and still reaches the catch-all.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ProblemDetail> handleIo(
            IOException exception, HttpServletRequest request, HttpServletResponse response) {
        String contentType = response.getContentType();
        if (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            log.debug("Stream client left before the response was written: {}", exception.toString());
            // Null marks the request as handled with nothing further to write.
            return null;
        }
        return handleUnexpected(exception, request);
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

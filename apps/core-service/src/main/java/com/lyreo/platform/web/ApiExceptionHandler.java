package com.lyreo.platform.web;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.platform.observability.CorrelationIdAccessor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central HTTP exception handler enforcing RFC 9457 Problem Details across all API endpoints.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        List<ApiFieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiFieldViolation(
                fe.getField(),
                fe.getCode() != null ? fe.getCode() : "Invalid",
                fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"
            ))
            .toList();

        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.BAD_REQUEST,
            "request-validation-failed",
            "Request validation failed",
            "One or more request fields are invalid.",
            ApiErrorCodes.REQUEST_VALIDATION_FAILED,
            resolveHttpServletRequest(request),
            errors
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
        HandlerMethodValidationException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        List<ApiFieldViolation> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String paramName = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                errors.add(new ApiFieldViolation(
                    paramName != null ? paramName : "parameter",
                    error.getCodes() != null && error.getCodes().length > 0 ? error.getCodes()[0] : "Invalid",
                    error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value"
                ));
            });
        });

        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.BAD_REQUEST,
            "request-validation-failed",
            "Request validation failed",
            "One or more request parameters are invalid.",
            ApiErrorCodes.REQUEST_VALIDATION_FAILED,
            resolveHttpServletRequest(request),
            errors
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
        HttpMessageNotReadableException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.BAD_REQUEST,
            "malformed-request",
            "Malformed request",
            "Failed to read or parse request payload.",
            ApiErrorCodes.MALFORMED_REQUEST,
            resolveHttpServletRequest(request),
            null
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
        MissingServletRequestParameterException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        List<ApiFieldViolation> errors = List.of(new ApiFieldViolation(
            ex.getParameterName(),
            "MissingParameter",
            "Required request parameter '" + ex.getParameterName() + "' is missing"
        ));
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.BAD_REQUEST,
            "request-validation-failed",
            "Request validation failed",
            "Required request parameter is missing.",
            ApiErrorCodes.REQUEST_VALIDATION_FAILED,
            resolveHttpServletRequest(request),
            errors
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
        HttpRequestMethodNotSupportedException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.METHOD_NOT_ALLOWED,
            "method-not-allowed",
            "Method not allowed",
            ex.getMessage(),
            ApiErrorCodes.METHOD_NOT_ALLOWED,
            resolveHttpServletRequest(request),
            null
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
        HttpMediaTypeNotSupportedException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "unsupported-media-type",
            "Unsupported media type",
            ex.getMessage(),
            ApiErrorCodes.UNSUPPORTED_MEDIA_TYPE,
            resolveHttpServletRequest(request),
            null
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
        NoResourceFoundException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.NOT_FOUND,
            "resource-not-found",
            "Resource not found",
            ex.getMessage(),
            ApiErrorCodes.RESOURCE_NOT_FOUND,
            resolveHttpServletRequest(request),
            null
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.NOT_FOUND);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
        TypeMismatchException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.BAD_REQUEST,
            "malformed-request",
            "Malformed request",
            "Type mismatch for parameter: " + ex.getPropertyName(),
            ApiErrorCodes.MALFORMED_REQUEST,
            resolveHttpServletRequest(request),
            null
        );
        return new ResponseEntity<>(problem, problemHeaders(headers), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.NOT_FOUND,
            "resource-not-found",
            "Resource not found",
            ex.getMessage(),
            ApiErrorCodes.RESOURCE_NOT_FOUND,
            request,
            null
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    @ExceptionHandler(StateConflictException.class)
    public ResponseEntity<ProblemDetail> handleStateConflict(StateConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.CONFLICT,
            "state-conflict",
            "State conflict",
            ex.getMessage(),
            ApiErrorCodes.STATE_CONFLICT,
            request,
            null
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ProblemDetail> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.BAD_REQUEST,
            "request-validation-failed",
            "Request validation failed",
            ex.getMessage(),
            ApiErrorCodes.REQUEST_VALIDATION_FAILED,
            request,
            null
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.FORBIDDEN,
            "access-denied",
            "Access denied",
            "Access is denied to this resource.",
            ApiErrorCodes.ACCESS_DENIED,
            request,
            null
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .contentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.UNAUTHORIZED,
            "authentication-required",
            "Authentication required",
            "Authentication is required to access this resource.",
            ApiErrorCodes.AUTHENTICATION_REQUIRED,
            request,
            null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String slug = status.name().toLowerCase().replace('_', '-');
        String code = status.is4xxClientError() ? "CLIENT_ERROR" : "SERVER_ERROR";
        if (status == HttpStatus.UNAUTHORIZED) code = ApiErrorCodes.AUTHENTICATION_REQUIRED;
        if (status == HttpStatus.FORBIDDEN) code = ApiErrorCodes.ACCESS_DENIED;
        if (status == HttpStatus.NOT_FOUND) code = ApiErrorCodes.RESOURCE_NOT_FOUND;

        ProblemDetail problem = ApiProblemFactory.create(
            status,
            slug,
            status.getReasonPhrase(),
            ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(),
            code,
            request,
            null
        );
        return ResponseEntity.status(status)
            .contentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationIdAccessor.get(request);
        log.error("Unhandled server exception [correlationId={}]", correlationId, ex);

        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Internal server error",
            "An unexpected server error occurred.",
            ApiErrorCodes.INTERNAL_ERROR,
            request,
            null
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    private static HttpServletRequest resolveHttpServletRequest(WebRequest request) {
        if (request instanceof ServletWebRequest swr) {
            return swr.getRequest();
        }
        return null;
    }

    private static HttpHeaders problemHeaders(HttpHeaders original) {
        HttpHeaders headers = new HttpHeaders();
        if (original != null) {
            headers.putAll(original);
        }
        headers.setContentType(ApiProblemWriter.APPLICATION_PROBLEM_JSON);
        return headers;
    }
}

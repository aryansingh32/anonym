package com.messagingCluster.Messenger.Exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global Exception Handler for ANonym Backend
 *
 * This class handles exceptions thrown by REST controllers
 * and returns consistent error responses to clients.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle authentication/authorization errors
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        log.error("🚫 Access denied: {}", ex.getMessage());

        Map<String, Object> errorResponse = buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "Access Denied",
                ex.getMessage(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Handle illegal argument exceptions (validation errors)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.error("❌ Invalid argument: {}", ex.getMessage());

        Map<String, Object> errorResponse = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid Request",
                ex.getMessage(),
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handle Redis connection errors
     */
    @ExceptionHandler(org.springframework.data.redis.RedisConnectionFailureException.class)
    public ResponseEntity<?> handleRedisConnectionFailure(Exception ex, WebRequest request) {
        log.error("🔴 Redis connection failed: {}", ex.getMessage());

        Map<String, Object> errorResponse = buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Temporarily Unavailable",
                "Unable to connect to session store. Please try again later.",
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    /**
     * Handle all other unexpected exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception ex, WebRequest request) {
        log.error("💥 Unexpected error: {}", ex.getMessage(), ex);

        Map<String, Object> errorResponse = buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred. Please try again later.",
                request.getDescription(false)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    /**
     * Build consistent error response structure
     */
    private Map<String, Object> buildErrorResponse(
            HttpStatus status,
            String error,
            String message,
            String path) {

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", status.value());
        errorResponse.put("error", error);
        errorResponse.put("message", message);
        errorResponse.put("path", path.replace("uri=", ""));

        return errorResponse;
    }
}
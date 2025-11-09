package com.messagingCluster.Messenger.controller;

import com.messagingCluster.Messenger.services.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for Anonymous Identity Management
 *
 * Endpoints:
 * - POST /api/identity/register : Create new anonymous identity
 * - POST /api/identity/revoke   : Logout/revoke current identity
 * - GET  /api/identity/status   : Check session status
 */
@RestController
@RequestMapping("/api/identity")
@RequiredArgsConstructor
@Slf4j
public class IdentityController {

    private final IdentityService identityService;

    /**
     * Register a new anonymous identity
     *
     * This endpoint is PUBLIC - no authentication required.
     * Creates a new Anonymous Code that the client will use for all future requests.
     *
     * Rate Limiting: Max 5 registrations per IP per hour (configured in IdentityService)
     *
     * Response Format:
     * {
     *   "anonymousCode": "4A7B-8F1C-229D-3G6H",
     *   "expiresInMinutes": 120,
     *   "message": "Anonymous identity created successfully"
     * }
     *
     * @param request HTTP request (for IP-based rate limiting)
     * @return ResponseEntity with anonymous code or error
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerNewAnonymousIdentity(HttpServletRequest request) {

        // Get client IP for rate limiting
        String clientIp = getClientIp(request);

        log.info("🆕 Registration request from IP: {}", clientIp);

        // Check rate limit
        if (!identityService.checkRateLimit(clientIp)) {
            log.warn("🚫 Registration blocked: Rate limit exceeded for IP {}", clientIp);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Rate limit exceeded");
            errorResponse.put("message", "Too many registration attempts. Please try again later.");
            errorResponse.put("retryAfterMinutes", 60);

            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse);
        }

        // Generate and store anonymous code
        String anonymousCode = identityService.generateAndStoreAnonymousCode();

        // Build success response
        Map<String, Object> response = new HashMap<>();
        response.put("anonymousCode", anonymousCode);
        response.put("expiresInMinutes", 120);
        response.put("message", "Anonymous identity created successfully");
        response.put("instructions", "Include this code in X-Anonymous-Code header for all requests");

        log.info("✅ Anonymous identity created: {} for IP: {}", anonymousCode, clientIp);

        return ResponseEntity.ok(response);
    }

    /**
     * Revoke current anonymous identity (logout)
     *
     * This endpoint requires authentication.
     * Deletes the Anonymous Code from Redis, effectively logging out the user.
     *
     * @param authentication Spring Security authentication (contains Anonymous Code)
     * @return ResponseEntity with success message
     */
    @PostMapping("/revoke")
    public ResponseEntity<?> revokeIdentity(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String anonymousCode = authentication.getName();

        log.info("🔒 Revocation request from: {}", anonymousCode);

        // Revoke the code
        identityService.revokeCode(anonymousCode);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Anonymous identity revoked successfully");
        response.put("code", anonymousCode);

        log.info("✅ Anonymous identity revoked: {}", anonymousCode);

        return ResponseEntity.ok(response);
    }

    /**
     * Check session status
     *
     * This endpoint requires authentication.
     * Returns information about the current session (TTL, statistics).
     *
     * Response Format:
     * {
     *   "anonymousCode": "4A7B-8F1C-229D-3G6H",
     *   "isActive": true,
     *   "remainingTimeSeconds": 7200,
     *   "message": "Session is active"
     * }
     *
     * @param authentication Spring Security authentication (contains Anonymous Code)
     * @return ResponseEntity with session status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getSessionStatus(Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String anonymousCode = authentication.getName();

        // Get remaining session time
        long remainingSeconds = identityService.getRemainingSessionTime(anonymousCode);

        Map<String, Object> response = new HashMap<>();
        response.put("anonymousCode", anonymousCode);
        response.put("isActive", remainingSeconds > 0);
        response.put("remainingTimeSeconds", remainingSeconds);
        response.put("remainingTimeMinutes", remainingSeconds / 60);

        if (remainingSeconds > 0) {
            response.put("message", "Session is active");
        } else {
            response.put("message", "Session expired or not found");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Extract client IP address from HTTP request
     * Handles X-Forwarded-For header for clients behind proxies/load balancers
     *
     * @param request HTTP request
     * @return Client IP address
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // If X-Forwarded-For contains multiple IPs, take the first one
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip != null ? ip : "UNKNOWN";
    }
}
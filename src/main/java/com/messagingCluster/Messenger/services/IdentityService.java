package com.messagingCluster.Messenger.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * Identity Management Service for Anonymous Authentication
 *
 * This service handles the creation and validation of Anonymous Codes,
 * which serve as ephemeral authentication tokens for users.
 *
 * Features:
 * - Generate cryptographically secure anonymous codes
 * - Store codes in Redis with automatic expiration (TTL)
 * - Validate and renew codes on each request (session refresh)
 * - Rate limiting to prevent abuse
 *
 * Redis Key Structure:
 * - anon:code:{CODE}     : Active user sessions
 * - anon:rate:{IP}       : Rate limiting for registration
 */
@Service
@Slf4j
public class IdentityService {

    private final RedisTemplate<String, String> redisTemplate;

    // Session configuration
    private static final long IDENTITY_TTL_MINUTES = 120;  // 2 hours of inactivity
    private static final long MAX_IDENTITY_TTL_HOURS = 24; // Hard limit: 24 hours max session

    // Rate limiting configuration
    private static final int MAX_REGISTRATIONS_PER_IP = 5;   // Max codes per IP
    private static final long RATE_LIMIT_WINDOW_HOURS = 1;   // Per hour

    // Secure random for code generation
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    public IdentityService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Generate a new anonymous code and store it in Redis
     *
     * Code Format: XXXX-XXXX-XXXX-XXXX (16 characters, dash-separated)
     * Example: 4A7B-8F1C-229D-3G6H
     *
     * @return The generated anonymous code
     */
    public String generateAndStoreAnonymousCode() {
        // Generate cryptographically secure random code
        String code = generateSecureCode();
        String key = "anon:code:" + code;

        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        // Store code with initial TTL
        // Value contains creation timestamp for hard limit enforcement
        String value = String.valueOf(System.currentTimeMillis());
        ops.set(key, value, IDENTITY_TTL_MINUTES, TimeUnit.MINUTES);

        log.info("🆕 New anonymous identity created: {} (TTL: {} min)",
                code, IDENTITY_TTL_MINUTES);

        return code;
    }

    /**
     * Generate a cryptographically secure random code
     *
     * @return 16-character code formatted as XXXX-XXXX-XXXX-XXXX
     */
    private String generateSecureCode() {
        StringBuilder code = new StringBuilder();

        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-'); // Add dash every 4 characters
            }
            int randomIndex = SECURE_RANDOM.nextInt(CODE_CHARS.length());
            code.append(CODE_CHARS.charAt(randomIndex));
        }

        return code.toString();
    }

    /**
     * Validate a code and renew its TTL (session refresh)
     *
     * This method is called on every authenticated request to:
     * 1. Verify the code exists in Redis (not expired)
     * 2. Refresh the TTL to extend the session
     * 3. Enforce hard limit (max 24 hours total)
     *
     * @param code The anonymous code to validate
     * @return true if valid and renewed, false if invalid/expired
     */
    public boolean isCodeValidAndRenew(String code) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }

        String key = "anon:code:" + code;
        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        // Check if code exists
        String value = ops.get(key);

        if (value != null) {
            try {
                // Check hard limit: has it been more than 24 hours since creation?
                long creationTime = Long.parseLong(value);
                long hoursSinceCreation =
                        (System.currentTimeMillis() - creationTime) / (1000 * 60 * 60);

                if (hoursSinceCreation >= MAX_IDENTITY_TTL_HOURS) {
                    log.warn("⏰ Session expired: {} (exceeded {} hour hard limit)",
                            code, MAX_IDENTITY_TTL_HOURS);
                    redisTemplate.delete(key); // Clean up
                    return false;
                }

                // Renew TTL: Reset expiration timer
                redisTemplate.expire(key, IDENTITY_TTL_MINUTES, TimeUnit.MINUTES);

                log.debug("✅ Session renewed: {}", code);
                return true;

            } catch (NumberFormatException e) {
                log.error("❌ Invalid session data format for code: {}", code);
                return false;
            }
        }

        log.debug("❌ Session not found: {}", code);
        return false;
    }

    /**
     * Validate a code without renewing TTL (read-only check)
     *
     * @param code The anonymous code to check
     * @return true if valid, false if invalid/expired
     */
    public boolean isCodeValid(String code) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }

        String key = "anon:code:" + code;
        Boolean exists = redisTemplate.hasKey(key);

        return Boolean.TRUE.equals(exists);
    }

    /**
     * Check rate limiting for code generation (prevent abuse)
     *
     * @param clientIp The IP address of the requesting client
     * @return true if rate limit not exceeded, false if blocked
     */
    public boolean checkRateLimit(String clientIp) {
        if (clientIp == null) {
            return true; // Allow if IP not available
        }

        String rateLimitKey = "anon:rate:" + clientIp;
        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        // Increment counter
        Long count = ops.increment(rateLimitKey);

        if (count == null) {
            count = 0L;
        }

        // Set expiration on first request
        if (count == 1) {
            redisTemplate.expire(rateLimitKey, RATE_LIMIT_WINDOW_HOURS, TimeUnit.HOURS);
        }

        if (count > MAX_REGISTRATIONS_PER_IP) {
            log.warn("🚫 Rate limit exceeded for IP: {} (count: {})", clientIp, count);
            return false;
        }

        return true;
    }

    /**
     * Revoke a code (logout/disconnect)
     *
     * @param code The anonymous code to revoke
     */
    public void revokeCode(String code) {
        if (code == null) {
            return;
        }

        String key = "anon:code:" + code;
        Boolean deleted = redisTemplate.delete(key);

        if (Boolean.TRUE.equals(deleted)) {
            log.info("🔒 Anonymous code revoked: {}", code);
        }
    }

    /**
     * Get session statistics for monitoring
     *
     * @param code The anonymous code
     * @return Remaining TTL in seconds, or -1 if not found
     */
    public long getRemainingSessionTime(String code) {
        if (code == null) {
            return -1;
        }

        String key = "anon:code:" + code;
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        return ttl != null ? ttl : -1;
    }
}
package com.messagingCluster.Messenger.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Message Service for handling message-related operations
 *
 * This service provides:
 * - Message rate limiting (prevent spam)
 * - Message metrics tracking
 * - Group/room membership validation (future feature)
 *
 * Redis Key Structure:
 * - msg:rate:{CODE}      : Message count for rate limiting
 * - msg:metrics:{CODE}   : Total messages sent by user
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {

    private final RedisTemplate<String, String> redisTemplate;

    // Rate limiting configuration
    private static final int MAX_MESSAGES_PER_MINUTE = 30;  // 30 messages per minute
    private static final int RATE_LIMIT_WINDOW_SECONDS = 60; // 1 minute window

    /**
     * Check if user has exceeded message rate limit
     *
     * @param userCode The anonymous code of the sender
     * @return true if allowed to send, false if rate limited
     */
    public boolean checkMessageRateLimit(String userCode) {
        if (userCode == null) {
            return false;
        }

        String rateLimitKey = "msg:rate:" + userCode;
        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        // Increment message count
        Long count = ops.increment(rateLimitKey);

        if (count == null) {
            count = 0L;
        }

        // Set expiration on first message
        if (count == 1) {
            redisTemplate.expire(rateLimitKey, RATE_LIMIT_WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        // Check if limit exceeded
        if (count > MAX_MESSAGES_PER_MINUTE) {
            log.warn("🚫 Message rate limit exceeded for user: {} (count: {})",
                    userCode, count);
            return false;
        }

        return true;
    }

    /**
     * Record a sent message (for metrics/monitoring)
     *
     * @param userCode The anonymous code of the sender
     */
    public void recordMessage(String userCode) {
        if (userCode == null) {
            return;
        }

        String metricsKey = "msg:metrics:" + userCode;
        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        // Increment total message count
        ops.increment(metricsKey);

        // Set expiration to match session TTL
        redisTemplate.expire(metricsKey, 2, TimeUnit.HOURS);
    }

    /**
     * Get message statistics for a user
     *
     * @param userCode The anonymous code
     * @return Total messages sent in current session, or 0
     */
    public long getMessageCount(String userCode) {
        if (userCode == null) {
            return 0;
        }

        String metricsKey = "msg:metrics:" + userCode;
        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        String count = ops.get(metricsKey);

        try {
            return count != null ? Long.parseLong(count) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Check if user is a member of a group (future feature)
     *
     * This will be implemented when group chat functionality is added.
     * For now, it returns true to allow P2P messaging.
     *
     * @param userCode The anonymous code of the user
     * @param groupCode The group identifier
     * @return true if user is a member, false otherwise
     */
    public boolean checkGroupMembership(String userCode, String groupCode) {
        // TODO: Implement Redis SET-based group membership
        // SMEMBERS group:{groupCode} to check if userCode is a member
        return true;
    }

    /**
     * Validate message content (basic sanity checks)
     *
     * @param encryptedContent The encrypted message content
     * @return true if valid, false if invalid
     */
    public boolean validateMessageContent(String encryptedContent) {
        if (encryptedContent == null || encryptedContent.trim().isEmpty()) {
            return false;
        }

        // Check maximum message size (prevent DOS attacks)
        // Encrypted content should be reasonably sized
        int maxSize = 100_000; // 100KB max (generous for E2EE overhead)

        if (encryptedContent.length() > maxSize) {
            log.warn("❌ Message rejected: Content too large ({} bytes)",
                    encryptedContent.length());
            return false;
        }

        return true;
    }
}
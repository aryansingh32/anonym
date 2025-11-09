package com.messagingCluster.Messenger.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health Check Controller
 *
 * Provides health status endpoints for monitoring and load balancers.
 * These endpoints are publicly accessible (no authentication required).
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Basic health check endpoint
     *
     * @return 200 OK if service is running
     */
    @GetMapping
    public ResponseEntity<?> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "ANonym Messenger");
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    /**
     * Detailed health check with dependency status
     *
     * @return Health status including Redis connectivity
     */
    @GetMapping("/detailed")
    public ResponseEntity<?> detailedHealth() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "ANonym Messenger");
        response.put("timestamp", System.currentTimeMillis());

        // Check Redis connectivity
        boolean redisHealthy = checkRedisHealth();

        response.put("redis", redisHealthy ? "UP" : "DOWN");
        response.put("status", redisHealthy ? "UP" : "DEGRADED");

        return ResponseEntity.ok(response);
    }

    /**
     * Check Redis connectivity
     *
     * @return true if Redis is reachable, false otherwise
     */
    private boolean checkRedisHealth() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
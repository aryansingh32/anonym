package com.messagingCluster.Messenger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Configuration for ANonym Backend
 *
 * This configuration sets up RedisTemplate with proper serializers
 * for string-based key-value operations.
 *
 * Redis is used for:
 * - Anonymous Code storage (ephemeral sessions)
 * - Rate limiting counters
 * - Message metrics
 * - Future: Group membership, online status
 */
@Configuration
public class RedisConfig {

    /**
     * Configure RedisTemplate with String serializers
     *
     * @param connectionFactory Auto-configured by Spring Boot
     * @return Configured RedisTemplate
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();

        // Set connection factory
        template.setConnectionFactory(connectionFactory);

        // Use String serializers for both keys and values
        StringRedisSerializer serializer = new StringRedisSerializer();

        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);

        // Enable transaction support if needed
        template.setEnableTransactionSupport(false);

        template.afterPropertiesSet();

        return template;
    }
}
package com.messagingCluster.Messenger.config;

import com.messagingCluster.Messenger.services.IdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Collections;

/**
 * WebSocket Configuration for ANonym Real-Time Messaging
 *
 * This configuration:
 * 1. Sets up STOMP endpoints for WebSocket connections
 * 2. Configures message broker for pub/sub messaging
 * 3. Implements authentication interceptor for WebSocket frames
 *
 * Architecture:
 * - Clients connect via /ws endpoint (with SockJS fallback)
 * - Messages are sent to /app/* destinations (application prefix)
 * - Broker routes messages to /topic/* destinations (subscriptions)
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final IdentityService identityService;

    /**
     * TaskScheduler bean for WebSocket heartbeat management
     * This scheduler handles periodic ping/pong messages to keep connections alive
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);  // Number of threads for scheduled tasks
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Register STOMP endpoints that clients will connect to
     *
     * Endpoint: /ws
     * - Primary WebSocket endpoint
     * - SockJS fallback for browsers without WebSocket support
     * - Allows all origins (mobile app + web access)
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // Allow all origins (for mobile + Tor)
                .withSockJS()                    // Enable SockJS fallback
                .setHeartbeatTime(25000);        // Keep connection alive every 25s
    }

    /**
     * Configure the message broker for routing messages
     *
     * Application Destination Prefix: /app
     * - Messages sent to /app/* are routed to @MessageMapping methods
     *
     * Simple Broker: /topic
     * - In-memory broker for pub/sub messaging
     * - Subscribers to /topic/* receive broadcasted messages
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{25000, 25000})
                .setTaskScheduler(taskScheduler());; // [server, client] heartbeat
    }

    /**
     * Configure client inbound channel with authentication interceptor
     *
     * This interceptor extracts the X-Anonymous-Code from WebSocket headers
     * and authenticates the user before processing any MESSAGE frames
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {

            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // Extract Anonymous Code from CONNECT frame headers
                    String anonymousCode = accessor.getFirstNativeHeader("X-Anonymous-Code");

                    if (anonymousCode != null && identityService.isCodeValidAndRenew(anonymousCode)) {
                        // Authenticate the WebSocket session with the Anonymous Code
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        new User(anonymousCode, "", Collections.emptyList()),
                                        null,
                                        Collections.emptyList()
                                );

                        // Store authentication in the WebSocket session
                        accessor.setUser(authentication);
                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        System.out.println("✅ WebSocket authenticated: " + anonymousCode);
                    } else {
                        System.err.println("❌ WebSocket authentication failed: Invalid code");
                        // Let security config handle the rejection
                    }
                }

                return message;
            }
        });
    }
}
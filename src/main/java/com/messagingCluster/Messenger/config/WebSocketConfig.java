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
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.Collections;

/**
 * ✅ FIXED: WebSocket Configuration with Railway.app Optimizations
 * 
 * Changes:
 * 1. Reduced heartbeat interval (10s instead of 25s)
 * 2. Increased message size limits
 * 3. Better connection timeout handling
 * 4. Optimized for cloud deployment
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final IdentityService identityService;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(20);  // ✅ Increased from 10 to handle more connections
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setHeartbeatTime(10000)  // ✅ CRITICAL: Reduced to 10s (Railway timeout fix)
                .setDisconnectDelay(5000)  // ✅ Quick disconnect detection
                .setHttpMessageCacheSize(1000)
                .setStreamBytesLimit(512 * 1024); // ✅ 512KB limit
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000})  // ✅ Reduced to 10s both ways
                .setTaskScheduler(taskScheduler());
    }

    /**
     * ✅ FIXED: Better transport configuration
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(128 * 1024)  // ✅ 128KB per message
                .setSendBufferSizeLimit(512 * 1024)  // ✅ 512KB send buffer
                .setSendTimeLimit(20 * 1000)  // ✅ 20s send timeout
                .setTimeToFirstMessage(30 * 1000);  // ✅ 30s for first message
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {

            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String anonymousCode = accessor.getFirstNativeHeader("X-Anonymous-Code");

                    if (anonymousCode != null && identityService.isCodeValidAndRenew(anonymousCode)) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        new User(anonymousCode, "", Collections.emptyList()),
                                        null,
                                        Collections.emptyList()
                                );

                        accessor.setUser(authentication);
                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        System.out.println("✅ WebSocket authenticated: " + anonymousCode);
                    } else {
                        System.err.println("❌ WebSocket authentication failed: Invalid code");
                    }
                }

                return message;
            }
        });
    }
}

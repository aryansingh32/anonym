package com.messagingCluster.Messenger.config;

import com.messagingCluster.Messenger.services.IdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * HTTP Security Configuration for ANonym Backend
 *
 * Security Model:
 * - Stateless authentication using Anonymous Codes (no sessions/cookies)
 * - WebSocket endpoints are publicly accessible (auth handled in WS layer)
 * - Registration endpoint is public (creates new anonymous identities)
 * - All other REST endpoints require valid Anonymous Code
 *
 * Authentication Flow:
 * 1. Client calls /api/identity/register (no auth)
 * 2. Receives Anonymous Code
 * 3. Includes X-Anonymous-Code header in all subsequent requests
 * 4. AnonymousCodeFilter validates and renews the code
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final IdentityService identityService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF: We're using stateless auth with custom tokens
                .csrf(csrf -> csrf.disable())

                // Configure authorization rules
                .authorizeHttpRequests(auth -> auth
                        // PUBLIC ENDPOINTS

                        // 1. WebSocket handshake endpoint (auth happens in WS layer)
                        .requestMatchers("/ws/**").permitAll()

                        // 2. Anonymous identity registration (creates new codes)
                        .requestMatchers("/api/identity/register").permitAll()

                        // 3. Health check endpoint (for monitoring/load balancers)
                        .requestMatchers("/actuator/health").permitAll()

                        // PROTECTED ENDPOINTS

                        // All other REST API calls require authentication
                        .anyRequest().authenticated()
                )

                // Add custom filter to validate Anonymous Code on every request
                .addFilterBefore(
                        new AnonymousCodeFilter(identityService),
                        UsernamePasswordAuthenticationFilter.class
                )

                // Stateless session management (no server-side sessions)
                .sessionManagement(sess -> sess
                        .sessionCreationPolicy(
                                org.springframework.security.config.http.SessionCreationPolicy.STATELESS
                        )
                )

                // Disable HTTP Basic Auth popup in browsers
                .httpBasic(basic -> basic.disable())

                // Disable form login (we're using custom auth)
                .formLogin(form -> form.disable());

        return http.build();
    }
}
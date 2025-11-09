package com.messagingCluster.Messenger.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingCluster.Messenger.services.IdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom Security Filter for Anonymous Code Authentication
 *
 * This filter intercepts every HTTP request and:
 * 1. Extracts the X-Anonymous-Code header
 * 2. Validates the code against Redis
 * 3. Sets the Spring Security authentication context
 * 4. Renews the session TTL on valid requests
 *
 * Flow:
 * - If code is valid: Request proceeds with authentication
 * - If code is invalid: Request is rejected with 401 Unauthorized
 * - If no code provided: Request proceeds (public endpoints handle this)
 */
@Slf4j
public class AnonymousCodeFilter extends OncePerRequestFilter {

    private final IdentityService identityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnonymousCodeFilter(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        log.debug("🔍 Filter processing: {} {}", method, requestUri);

        // 1. BYPASS: Allow public endpoints without authentication
        if (isPublicEndpoint(requestUri)) {
            log.debug("✅ Public endpoint, bypassing authentication: {}", requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        // 2. EXTRACT: Get Anonymous Code from request header
        String anonymousCode = request.getHeader("X-Anonymous-Code");

        if (anonymousCode == null || anonymousCode.trim().isEmpty()) {
            // No code provided - let Spring Security handle the 401 response
            log.debug("⚠️ No Anonymous Code provided for: {}", requestUri);
            filterChain.doFilter(request, response);
            return;
        }

        // 3. VALIDATE: Check if code is valid and renew session
        if (identityService.isCodeValidAndRenew(anonymousCode)) {

            // 4. AUTHENTICATE: Create Spring Security authentication token
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            new User(anonymousCode, "", Collections.emptyList()),
                            null,
                            Collections.emptyList()
                    );

            // 5. SET CONTEXT: Store authentication for this request
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("✅ Authentication successful: {}", anonymousCode);

        } else {
            // 6. REJECTION: Invalid or expired code
            log.warn("❌ Authentication failed: Invalid code {}", anonymousCode);

            // Return 401 Unauthorized with error details
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Unauthorized");
            errorResponse.put("message", "Invalid or expired Anonymous Code");
            errorResponse.put("code", "INVALID_ANONYMOUS_CODE");
            errorResponse.put("instructions", "Please register a new anonymous identity at /api/identity/register");

            response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
            return; // Don't proceed with filter chain
        }

        // 7. PROCEED: Continue with the request
        filterChain.doFilter(request, response);
    }

    /**
     * Check if the request URI is a public endpoint that doesn't require authentication
     *
     * @param requestUri The request URI
     * @return true if public, false if protected
     */
    private boolean isPublicEndpoint(String requestUri) {
        // List of public endpoints
        String[] publicEndpoints = {
                "/api/identity/register",  // Registration endpoint
                "/ws/",                    // WebSocket handshake
                "/actuator/health",        // Health check
                "/error"                   // Error page
        };

        for (String endpoint : publicEndpoints) {
            if (requestUri.startsWith(endpoint)) {
                return true;
            }
        }

        return false;
    }
}
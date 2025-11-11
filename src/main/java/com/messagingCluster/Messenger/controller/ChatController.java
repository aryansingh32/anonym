package com.messagingCluster.Messenger.controller;

import com.messagingCluster.Messenger.model.MessageModel;
import com.messagingCluster.Messenger.services.MessageService;
import com.messagingCluster.Messenger.services.IdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;

/**
 * ✅ FIXED: WebSocket Controller for Real-Time Messaging with Session Key Support
 *
 * This controller handles incoming chat messages sent via WebSocket
 * and routes them to the appropriate recipients.
 *
 * Message Flow:
 * 1. Client sends message to /app/sendMessage
 * 2. Controller validates sender authentication
 * 3. Controller validates receiver exists
 * 4. ✅ FIXED: Message is forwarded WITH ALL encryption fields (including encryptedSessionKey)
 * 5. Message is routed to receiver's topic: /topic/user/{receiverCode}
 *
 * Security:
 * - Sender impersonation prevention (validates authenticated user)
 * - Receiver validation (ensures recipient exists)
 * - E2EE content (server never decrypts messages)
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageService messageService;
    private final IdentityService identityService;

    /**
     * ✅ FIXED: Handle incoming chat messages from authenticated users
     *
     * @param message The encrypted message payload
     * @param authentication Spring Security authentication (contains Anonymous Code)
     */
    @MessageMapping("/sendMessage")
    public void sendMessage(@Payload MessageModel message, Authentication authentication) {

        // 1. SECURITY: Extract authenticated sender's code
        String authenticatedSender = authentication.getName();

        log.info("📨 Message received from: {} to: {}", authenticatedSender, message.getReceiver());

        // 2. VALIDATION: Prevent sender impersonation
        if (message.getSender() == null || !authenticatedSender.equals(message.getSender())) {
            log.error("🚨 SECURITY ALERT: Impersonation attempt detected!");
            log.error("   Authenticated: {} | Payload Sender: {}",
                    authenticatedSender, message.getSender());
            return;
        }

        // 3. VALIDATION: Ensure receiver exists in Redis
        if (message.getReceiver() == null ||
                !identityService.isCodeValid(message.getReceiver())) {
            log.error("❌ Message rejected: Invalid receiver code {}", message.getReceiver());

            // Send error notification back to sender
            MessageModel errorMsg = new MessageModel();
            errorMsg.setSender("SYSTEM");
            errorMsg.setReceiver(authenticatedSender);
            errorMsg.setEncryptedContent("ERROR: Recipient not found or session expired");
            errorMsg.setTimestamp(LocalDateTime.now());

            messagingTemplate.convertAndSend(
                    "/topic/user/" + authenticatedSender,
                    errorMsg
            );
            return;
        }

        // 4. VALIDATION: Check for content (either encrypted or plain)
        boolean hasEncryptedContent = message.getEncryptedContent() != null && 
                                     !message.getEncryptedContent().trim().isEmpty();
        boolean hasPlainContent = message.getContent() != null && 
                                 !message.getContent().trim().isEmpty();
        
        if (!hasEncryptedContent && !hasPlainContent) {
            log.error("❌ Message rejected: Empty content from {}", authenticatedSender);
            return;
        }

        // 5. RATE LIMITING: Check message rate limit
        if (!messageService.checkMessageRateLimit(authenticatedSender)) {
            log.warn("🚫 Message rejected: Rate limit exceeded for {}", authenticatedSender);
            
            MessageModel errorMsg = new MessageModel();
            errorMsg.setSender("SYSTEM");
            errorMsg.setReceiver(authenticatedSender);
            errorMsg.setEncryptedContent("ERROR: Rate limit exceeded. Please slow down.");
            errorMsg.setTimestamp(LocalDateTime.now());
            
            messagingTemplate.convertAndSend(
                    "/topic/user/" + authenticatedSender,
                    errorMsg
            );
            return;
        }

        // 6. MESSAGE METADATA: Set server timestamp
        message.setTimestamp(LocalDateTime.now());

        // 7. ✅ CRITICAL FIX: Log encryption details for debugging
        if (hasEncryptedContent) {
            log.debug("🔒 Encrypted message (length: {})", message.getEncryptedContent().length());
            
            if (message.getEncryptedSessionKey() != null && !message.getEncryptedSessionKey().isEmpty()) {
                log.info("🔑 NEW SESSION KEY included in message (length: {})", 
                        message.getEncryptedSessionKey().length());
            } else {
                log.debug("ℹ️  Using existing session (no session key)");
            }
            
            if (message.getSessionId() != null) {
                log.debug("🆔 Session ID: {}", message.getSessionId());
            }
        } else {
            log.info("📝 Plain text message (length: {})", message.getContent().length());
        }

        // 8. ✅ ROUTING: Forward COMPLETE message to receiver
        // THIS IS THE CRITICAL FIX - we forward the ENTIRE message object
        // including encryptedSessionKey, sessionId, and all other fields
        String receiverTopic = "/topic/user/" + message.getReceiver();
        messagingTemplate.convertAndSend(receiverTopic, message);
        
        log.info("✅ Message forwarded to receiver: {}", message.getReceiver());

        // 9. OPTIONAL: Echo back to sender for multi-device sync
        // UNCOMMENT if you want sender to receive their own messages
        // String senderTopic = "/topic/user/" + message.getSender();
        // messagingTemplate.convertAndSend(senderTopic, message);
        // log.info("✅ Message echoed to sender: {}", message.getSender());

        // 10. METRICS: Record message for monitoring
        messageService.recordMessage(authenticatedSender);
    }

    /**
     * Handle typing indicator notifications
     *
     * @param typingNotification Contains sender and receiver codes
     * @param authentication Spring Security authentication
     */
    @MessageMapping("/typing")
    public void handleTypingIndicator(@Payload TypingNotification typingNotification,
                                      Authentication authentication) {

        String authenticatedSender = authentication.getName();

        // Validate sender
        if (!authenticatedSender.equals(typingNotification.getSender())) {
            log.error("🚨 Typing indicator: Sender mismatch");
            return;
        }

        // Validate receiver exists
        if (!identityService.isCodeValid(typingNotification.getReceiver())) {
            log.debug("⚠️ Typing indicator: Invalid receiver {}", typingNotification.getReceiver());
            return;
        }

        // Send typing indicator to receiver only
        String receiverTopic = "/topic/user/" + typingNotification.getReceiver();
        messagingTemplate.convertAndSend(receiverTopic, typingNotification);

        log.debug("⌨️ Typing indicator: {} -> {} (typing: {})",
                typingNotification.getSender(),
                typingNotification.getReceiver(),
                typingNotification.isTyping());
    }

    /**
     * ✅ NEW: Handle key exchange requests
     * Used when clients want to explicitly request public keys from peers
     */
    @MessageMapping("/requestKeyExchange")
    public void requestKeyExchange(@Payload MessageModel request, Authentication authentication) {
        
        String authenticatedSender = authentication.getName();

        // Validate sender
        if (!authenticatedSender.equals(request.getSender())) {
            log.error("🚨 Key exchange: Sender mismatch");
            return;
        }

        // Validate receiver exists
        if (!identityService.isCodeValid(request.getReceiver())) {
            log.debug("⚠️ Key exchange: Invalid receiver {}", request.getReceiver());
            return;
        }

        log.info("🔑 Key exchange request: {} -> {}", 
                request.getSender(), request.getReceiver());

        // Forward request to receiver
        String receiverTopic = "/topic/user/" + request.getReceiver();
        messagingTemplate.convertAndSend(receiverTopic, request);

        log.info("✅ Key exchange request forwarded");
    }

    /**
     * ✅ NEW: Handle public key sharing
     * Used when clients want to share their public keys with peers
     */
    @MessageMapping("/sharePublicKey")
    public void sharePublicKey(@Payload MessageModel keyData, Authentication authentication) {
        
        String authenticatedSender = authentication.getName();

        // Validate sender
        if (!authenticatedSender.equals(keyData.getSender())) {
            log.error("🚨 Public key share: Sender mismatch");
            return;
        }

        // Validate receiver exists
        if (!identityService.isCodeValid(keyData.getReceiver())) {
            log.debug("⚠️ Public key share: Invalid receiver {}", keyData.getReceiver());
            return;
        }

        log.info("🔑 Public key shared: {} -> {}", 
                keyData.getSender(), keyData.getReceiver());

        // Forward public key to receiver
        String receiverTopic = "/topic/user/" + keyData.getReceiver();
        messagingTemplate.convertAndSend(receiverTopic, keyData);

        log.info("✅ Public key forwarded");
    }

    /**
     * Simple typing notification model
     */
    public static class TypingNotification {
        private String sender;
        private String receiver;
        private boolean isTyping;

        // Getters and setters
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        public String getReceiver() { return receiver; }
        public void setReceiver(String receiver) { this.receiver = receiver; }
        public boolean isTyping() { return isTyping; }
        public void setTyping(boolean typing) { isTyping = typing; }
    }
}

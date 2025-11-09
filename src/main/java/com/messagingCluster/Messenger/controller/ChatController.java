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
 * WebSocket Controller for Real-Time Messaging
 *
 * This controller handles incoming chat messages sent via WebSocket
 * and routes them to the appropriate recipients.
 *
 * Message Flow:
 * 1. Client sends message to /app/sendMessage
 * 2. Controller validates sender authentication
 * 3. Controller validates receiver exists
 * 4. Message is routed to receiver's topic: /topic/user/{receiverCode}
 * 5. Message is echoed back to sender's topic: /topic/user/{senderCode}
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
     * Handle incoming chat messages from authenticated users
     *
     * @param message The encrypted message payload
     * @param authentication Spring Security authentication (contains Anonymous Code)
     */
    @MessageMapping("/sendMessage")
    public void sendMessage(@Payload MessageModel message, Authentication authentication) {

        // 1. SECURITY: Extract authenticated sender's code
        // This is set by WebSocketConfig's authentication interceptor
        String authenticatedSender = authentication.getName();

        log.info("📨 Message received from: {}", authenticatedSender);

        // 2. VALIDATION: Prevent sender impersonation
        // The payload's sender field must match the authenticated user
        if (message.getSender() == null || !authenticatedSender.equals(message.getSender())) {
            log.error("🚨 SECURITY ALERT: Impersonation attempt detected!");
            log.error("   Authenticated: {} | Payload Sender: {}",
                    authenticatedSender, message.getSender());
            return; // Reject the message silently
        }

        // 3. VALIDATION: Ensure receiver exists in Redis
        // This prevents messages being sent to non-existent or expired codes
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

        // 4. VALIDATION: Basic message integrity checks
        if (message.getEncryptedContent() == null ||
                message.getEncryptedContent().trim().isEmpty()) {
            log.error("❌ Message rejected: Empty content");
            return;
        }

        // 5. MESSAGE METADATA: Set server timestamp
        // Note: The client may also include their own timestamp for E2EE verification
        message.setTimestamp(LocalDateTime.now());

        // 6. ROUTING: Send message to receiver
        String receiverTopic = "/topic/user/" + message.getReceiver();
        messagingTemplate.convertAndSend(receiverTopic, message);
        log.info("✅ Message sent to receiver: {}", message.getReceiver());

        // 7. ECHO: Send message back to sender (for multi-device sync)
//        String senderTopic = "/topic/user/" + message.getSender();
//        messagingTemplate.convertAndSend(senderTopic, message);
//        log.info("✅ Message echoed to sender: {}", message.getSender());

        // 8. OPTIONAL: Update message metrics (for rate limiting/monitoring)
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

        // Send typing indicator to receiver only (don't echo back)
        String receiverTopic = "/topic/user/" + typingNotification.getReceiver();
        messagingTemplate.convertAndSend(receiverTopic, typingNotification);

        log.debug("⌨️ Typing indicator: {} -> {}",
                typingNotification.getSender(),
                typingNotification.getReceiver());
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
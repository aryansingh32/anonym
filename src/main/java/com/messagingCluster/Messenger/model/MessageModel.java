package com.messagingCluster.Messenger.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ✅ COMPLETE: Message Model with Full E2EE Support
 * 
 * This model handles both encrypted (mobile app) and plain text (web) messages.
 * It includes all necessary fields for hybrid encryption with session keys.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageModel {
    
    // ============ Basic Message Fields ============
    
    /**
     * Sender's anonymous code
     */
    private String sender;
    
    /**
     * Receiver's anonymous code
     */
    private String receiver;
    
    /**
     * Message timestamp (set by server)
     */
    private LocalDateTime timestamp;
    
    // ============ Encryption Fields (Mobile App) ============
    
    /**
     * ✅ CRITICAL: The AES-encrypted message content (Base64 encoded)
     * This field is used by the mobile app for E2EE messages
     */
    private String encryptedContent;
    
    /**
     * ✅ CRITICAL: The RSA-encrypted session key (Base64 encoded)
     * Only included in the FIRST message of a new session
     * Contains the AES key + IV encrypted with receiver's public key
     */
    private String encryptedSessionKey;
    
    /**
     * ✅ CRITICAL: Session identifier
     * Hash of the session key, used to identify which session to use
     * Allows receiver to reuse existing session keys
     */
    private String sessionId;
    
    // ============ Plain Text Fields (Web Interface) ============
    
    /**
     * Plain text content (for unencrypted messages from web interface)
     * Only used when encryptedContent is null
     */
    private String content;
    
    // ============ Optional/Metadata Fields ============
    
    /**
     * Message ID (for tracking/acknowledgment)
     */
    private String messageId;
    
    /**
     * Message type (text, image, file, etc.)
     */
    private String messageType;
    
    /**
     * Public key (for key exchange messages)
     */
    private String publicKey;
    
    /**
     * Typing indicator flag (for typing notifications)
     */
    private Boolean isTyping;
    
    // ============ Helper Methods ============
    
    /**
     * Check if this is an encrypted message
     */
    public boolean isEncrypted() {
        return encryptedContent != null && !encryptedContent.isEmpty();
    }
    
    /**
     * Check if this message includes a new session key
     */
    public boolean hasNewSessionKey() {
        return encryptedSessionKey != null && !encryptedSessionKey.isEmpty();
    }
    
    /**
     * Check if this is a plain text message
     */
    public boolean isPlainText() {
        return content != null && !content.isEmpty();
    }
    
    /**
     * Validate message structure
     */
    public boolean isValid() {
        // Must have sender and receiver
        if (sender == null || receiver == null) {
            return false;
        }
        
        // Must have either encrypted content OR plain content OR be a system message
        return isEncrypted() || isPlainText() || isTyping != null || publicKey != null;
    }
    
    /**
     * Get display content (for logging - never log encrypted content in production)
     */
    public String getDisplayContent() {
        if (isEncrypted()) {
            return "[Encrypted: " + encryptedContent.substring(0, Math.min(20, encryptedContent.length())) + "...]";
        } else if (isPlainText()) {
            return content.substring(0, Math.min(50, content.length())) + (content.length() > 50 ? "..." : "");
        } else if (publicKey != null) {
            return "[Public Key Exchange]";
        } else if (isTyping != null) {
            return "[Typing Indicator: " + isTyping + "]";
        }
        return "[Unknown Message Type]";
    }
}

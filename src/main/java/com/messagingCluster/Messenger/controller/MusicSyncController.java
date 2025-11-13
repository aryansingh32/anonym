package com.messagingCluster.Messenger.controller;

import com.messagingCluster.Messenger.model.MusicSyncMessage;
import com.messagingCluster.Messenger.services.MusicChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Music Sync Controller for Real-Time Music Listening
 *
 * Features:
 * - Channel-based music rooms
 * - Queue management
 * - Playback synchronization
 * - Channel chat
 */
@RestController  // ✅ ONLY use @RestController for REST endpoints
@RequestMapping("/api/music")
@RequiredArgsConstructor
@Slf4j
public class MusicSyncController {

    private final SimpMessagingTemplate messagingTemplate;
    private final MusicChannelService musicChannelService;
    private final RestTemplate restTemplate = new RestTemplate();

    // ============ REST ENDPOINTS ============

    /**
     * Search Spotify tracks via backend proxy
     */
    @GetMapping("/spotify/search")
    public ResponseEntity<?> searchSpotify(@RequestParam String q) {
        try {
            log.info("🔍 Spotify search request: {}", q);
            
            String accessToken = musicChannelService.getSpotifyAccessToken();
            
            String url = "https://api.spotify.com/v1/search?q=" + q + "&type=track&limit=20";
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            
            org.springframework.http.HttpEntity<String> entity = 
                new org.springframework.http.HttpEntity<>(headers);
            
            ResponseEntity<Map> response = restTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.GET,
                entity,
                Map.class
            );
            
            log.info("✅ Spotify search successful");
            return ResponseEntity.ok(response.getBody());
            
        } catch (Exception e) {
            log.error("❌ Spotify search failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(
                Map.of("error", "Spotify search failed", "message", e.getMessage())
            );
        }
    }

    /**
     * Search YouTube videos via backend proxy
     */
    @GetMapping("/youtube/search")
    public ResponseEntity<?> searchYouTube(@RequestParam String q) {
        try {
            log.info("🔍 YouTube search request: {}", q);
            
            String apiKey = musicChannelService.getYouTubeApiKey();
            
            String url = "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet" +
                "&q=" + q +
                "&type=video" +
                "&videoCategoryId=10" +
                "&maxResults=20" +
                "&key=" + apiKey;
            
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            
            log.info("✅ YouTube search successful");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("❌ YouTube search failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(
                Map.of("error", "YouTube search failed", "message", e.getMessage())
            );
        }
    }

    /**
     * Get all available channels
     */
    @GetMapping("/channels")
    public ResponseEntity<?> getChannels(Authentication authentication) {
        try {
            List<Map<String, Object>> channels = musicChannelService.getAllChannels();
            return ResponseEntity.ok(Map.of("channels", channels));
        } catch (Exception e) {
            log.error("❌ Failed to get channels: {}", e.getMessage());
            return ResponseEntity.status(500).body(
                Map.of("error", "Failed to get channels")
            );
        }
    }

    /**
     * Create a new music channel
     */
    @PostMapping("/channels")
    public ResponseEntity<?> createChannel(
        @RequestBody Map<String, String> request,
        Authentication authentication
    ) {
        try {
            String channelName = request.get("name");
            String creatorCode = authentication.getName();
            
            String channelId = musicChannelService.createChannel(channelName, creatorCode);
            
            return ResponseEntity.ok(Map.of(
                "channelId", channelId,
                "message", "Channel created successfully"
            ));
            
        } catch (Exception e) {
            log.error("❌ Failed to create channel: {}", e.getMessage());
            return ResponseEntity.status(500).body(
                Map.of("error", "Failed to create channel")
            );
        }
    }

    /**
     * Delete a music channel
     */
    @DeleteMapping("/channels/{channelId}")
    public ResponseEntity<?> deleteChannel(
        @PathVariable String channelId,
        Authentication authentication
    ) {
        try {
            String userCode = authentication.getName();
            boolean deleted = musicChannelService.deleteChannel(channelId, userCode);
            
            if (deleted) {
                return ResponseEntity.ok(Map.of("message", "Channel deleted"));
            } else {
                return ResponseEntity.status(403).body(
                    Map.of("error", "Not authorized to delete this channel")
                );
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to delete channel: {}", e.getMessage());
            return ResponseEntity.status(500).body(
                Map.of("error", "Failed to delete channel")
            );
        }
    }

    // ============ HELPER METHODS ============

    /**
     * Broadcast message to all members in a channel
     */
    private void broadcastToChannel(String channelId, MusicSyncMessage message) {
        message.setType("music_sync");
        messagingTemplate.convertAndSend(
            "/topic/channel/" + channelId,
            message
        );
        
        log.debug("📤 Broadcast {} to channel {}", message.getAction(), channelId);
    }

    /**
     * Send current channel state to a specific user
     */
    private void sendChannelStateToUser(String channelId, String userCode) {
        Map<String, Object> state = musicChannelService.getChannelState(channelId);
        
        if (state != null) {
            messagingTemplate.convertAndSendToUser(
                userCode,
                "/queue/sync",
                state
            );
            
            log.debug("📤 Sent channel state to {}", userCode);
        }
    }
}

/**
 * SEPARATE Controller for WebSocket operations
 * This must be a separate @Controller (not @RestController)
 */
@Controller
@RequiredArgsConstructor
@Slf4j
class MusicWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final MusicChannelService musicChannelService;

    /**
     * Handle music sync messages (play, pause, seek, queue)
     */
    @MessageMapping("/musicSync")
    public void handleMusicSync(
        @Payload MusicSyncMessage message,
        Authentication authentication
    ) {
        String authenticatedUser = authentication.getName();
        
        log.info("🎵 Music sync: {} from {} in channel {}",
            message.getAction(), authenticatedUser, message.getChannelId());
        
        // Validate sender
        if (!authenticatedUser.equals(message.getUserCode())) {
            log.error("🚨 Impersonation attempt detected!");
            return;
        }
        
        String channelId = message.getChannelId();
        
        // Process action
        switch (message.getAction()) {
            case "join_channel":
                musicChannelService.addMemberToChannel(channelId, authenticatedUser);
                break;
                
            case "leave_channel":
                musicChannelService.removeMemberFromChannel(channelId, authenticatedUser);
                break;
                
            case "play":
            case "pause":
            case "resume":
            case "seek":
            case "stop":
                // Update channel state
                musicChannelService.updateChannelState(channelId, message);
                break;
                
            case "queue_add":
                musicChannelService.addToQueue(channelId, message.getTrackData());
                break;
                
            case "queue_remove":
                musicChannelService.removeFromQueue(channelId, message.getIndex());
                break;
                
            case "queue_reorder":
                musicChannelService.reorderQueue(
                    channelId,
                    message.getOldIndex(),
                    message.getNewIndex()
                );
                break;
                
            case "request_sync":
                // Send current channel state to requester
                sendChannelStateToUser(channelId, authenticatedUser);
                return; // Don't broadcast
        }
        
        // Broadcast to all channel members
        broadcastToChannel(channelId, message);
    }

    /**
     * Handle channel chat messages
     */
    @MessageMapping("/channelChat")
    public void handleChannelChat(
        @Payload Map<String, Object> message,
        Authentication authentication
    ) {
        String authenticatedUser = authentication.getName();
        String channelId = (String) message.get("channelId");
        
        log.info("💬 Chat in channel {} from {}", channelId, authenticatedUser);
        
        // Validate sender
        if (!authenticatedUser.equals(message.get("sender"))) {
            log.error("🚨 Chat impersonation attempt!");
            return;
        }
        
        // Add timestamp
        message.put("timestamp", System.currentTimeMillis());
        message.put("type", "channel_chat");
        
        // Broadcast to all channel members
        messagingTemplate.convertAndSend(
            "/topic/channel/" + channelId,
            message
        );
    }

    private void broadcastToChannel(String channelId, MusicSyncMessage message) {
        message.setType("music_sync");
        messagingTemplate.convertAndSend(
            "/topic/channel/" + channelId,
            message
        );
        
        log.debug("📤 Broadcast {} to channel {}", message.getAction(), channelId);
    }

    private void sendChannelStateToUser(String channelId, String userCode) {
        Map<String, Object> state = musicChannelService.getChannelState(channelId);
        
        if (state != null) {
            messagingTemplate.convertAndSendToUser(
                userCode,
                "/queue/sync",
                state
            );
            
            log.debug("📤 Sent channel state to {}", userCode);
        }
    }
}

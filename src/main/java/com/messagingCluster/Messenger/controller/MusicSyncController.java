// ========================================
// FILE 1: MusicRestController.java
// REST API endpoints for music search
// ========================================
package com.messagingCluster.Messenger.controller;

import com.messagingCluster.Messenger.services.MusicChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api/music")
@RequiredArgsConstructor
@Slf4j
public class MusicSyncController {

    private final MusicChannelService musicChannelService;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Search Spotify tracks via backend proxy
     */
    @GetMapping("/spotify/search")
    public ResponseEntity<?> searchSpotify(@RequestParam String q) {
        try {
            log.info("🔍 Spotify search request: {}", q);
            
            String accessToken = musicChannelService.getSpotifyAccessToken();
            
            String url = "https://api.spotify.com/v1/search?q=" + 
                        java.net.URLEncoder.encode(q, "UTF-8") + 
                        "&type=track&limit=20";
            
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
            
            log.info("✅ Spotify search successful for query: {}", q);
            return ResponseEntity.ok(response.getBody());
            
        } catch (Exception e) {
            log.error("❌ Spotify search failed: {}", e.getMessage(), e);
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
                "&q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                "&type=video" +
                "&videoCategoryId=10" +
                "&maxResults=20" +
                "&key=" + apiKey;
            
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            
            log.info("✅ YouTube search successful for query: {}", q);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("❌ YouTube search failed: {}", e.getMessage(), e);
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
            log.debug("📋 Getting all music channels");
            List<Map<String, Object>> channels = musicChannelService.getAllChannels();
            return ResponseEntity.ok(Map.of("channels", channels));
        } catch (Exception e) {
            log.error("❌ Failed to get channels: {}", e.getMessage(), e);
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
            
            log.info("🎵 Creating music channel: {} by {}", channelName, creatorCode);
            
            String channelId = musicChannelService.createChannel(channelName, creatorCode);
            
            return ResponseEntity.ok(Map.of(
                "channelId", channelId,
                "message", "Channel created successfully"
            ));
            
        } catch (Exception e) {
            log.error("❌ Failed to create channel: {}", e.getMessage(), e);
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
            log.info("🗑️ Delete request for channel {} by {}", channelId, userCode);
            
            boolean deleted = musicChannelService.deleteChannel(channelId, userCode);
            
            if (deleted) {
                return ResponseEntity.ok(Map.of("message", "Channel deleted"));
            } else {
                return ResponseEntity.status(403).body(
                    Map.of("error", "Not authorized to delete this channel")
                );
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to delete channel: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(
                Map.of("error", "Failed to delete channel")
            );
        }
    }

    /**
     * Test endpoint to verify API is working
     */
    @GetMapping("/test")
    public ResponseEntity<?> testEndpoint(Authentication authentication) {
        return ResponseEntity.ok(Map.of(
            "status", "OK",
            "message", "Music API is working",
            "user", authentication.getName(),
            "timestamp", System.currentTimeMillis()
        ));
    }
}


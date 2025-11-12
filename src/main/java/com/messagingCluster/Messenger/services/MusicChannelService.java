package com.messagingCluster.Messenger.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingCluster.Messenger.model.MusicSyncMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Music Channel Service
 * 
 * Manages music channels, queues, and synchronization state using Redis
 * 
 * Redis Key Structure:
 * - music:channel:{ID}           : Channel metadata
 * - music:channel:{ID}:members   : Set of member codes
 * - music:channel:{ID}:queue     : Queue of tracks (List)
 * - music:channel:{ID}:state     : Current playback state
 * - music:channels:list          : Set of all channel IDs
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MusicChannelService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${spotify.client.id:}")
    private String spotifyClientId;
    
    @Value("${spotify.client.secret:}")
    private String spotifyClientSecret;
    
    @Value("${youtube.api.key:}")
    private String youtubeApiKey;
    
    private static final long CHANNEL_TTL_HOURS = 24;
    private String cachedSpotifyToken = null;
    private long spotifyTokenExpiry = 0;

    // ============ CHANNEL MANAGEMENT ============

    /**
     * Create a new music channel
     */
    public String createChannel(String channelName, String creatorCode) {
        String channelId = "MUSIC_" + System.currentTimeMillis();
        
        Map<String, Object> channelData = new HashMap<>();
        channelData.put("id", channelId);
        channelData.put("name", channelName);
        channelData.put("creatorCode", creatorCode);
        channelData.put("createdAt", System.currentTimeMillis());
        channelData.put("isPublic", true);
        
        try {
            String channelJson = objectMapper.writeValueAsString(channelData);
            
            // Store channel metadata
            redisTemplate.opsForValue().set(
                "music:channel:" + channelId,
                channelJson,
                CHANNEL_TTL_HOURS,
                TimeUnit.HOURS
            );
            
            // Add creator as member
            redisTemplate.opsForSet().add(
                "music:channel:" + channelId + ":members",
                creatorCode
            );
            redisTemplate.expire(
                "music:channel:" + channelId + ":members",
                CHANNEL_TTL_HOURS,
                TimeUnit.HOURS
            );
            
            // Add to global channel list
            redisTemplate.opsForSet().add("music:channels:list", channelId);
            
            log.info("🎵 Created music channel: {} - {}", channelId, channelName);
            return channelId;
            
        } catch (Exception e) {
            log.error("❌ Failed to create channel: {}", e.getMessage());
            throw new RuntimeException("Failed to create channel", e);
        }
    }

    /**
     * Get all available channels
     */
    public List<Map<String, Object>> getAllChannels() {
        try {
            Set<String> channelIds = redisTemplate.opsForSet().members("music:channels:list");
            
            if (channelIds == null || channelIds.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<Map<String, Object>> channels = new ArrayList<>();
            
            for (String channelId : channelIds) {
                String channelJson = redisTemplate.opsForValue().get("music:channel:" + channelId);
                
                if (channelJson != null) {
                    Map<String, Object> channelData = objectMapper.readValue(
                        channelJson,
                        new TypeReference<Map<String, Object>>() {}
                    );
                    
                    // Add member count
                    Long memberCount = redisTemplate.opsForSet().size(
                        "music:channel:" + channelId + ":members"
                    );
                    channelData.put("memberCount", memberCount != null ? memberCount : 0);
                    
                    channels.add(channelData);
                }
            }
            
            return channels;
            
        } catch (Exception e) {
            log.error("❌ Failed to get channels: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Delete a channel (only creator can delete)
     */
    public boolean deleteChannel(String channelId, String userCode) {
        try {
            String channelJson = redisTemplate.opsForValue().get("music:channel:" + channelId);
            
            if (channelJson == null) {
                return false;
            }
            
            Map<String, Object> channelData = objectMapper.readValue(
                channelJson,
                new TypeReference<Map<String, Object>>() {}
            );
            
            String creatorCode = (String) channelData.get("creatorCode");
            
            // Only creator can delete
            if (!userCode.equals(creatorCode)) {
                return false;
            }
            
            // Delete all channel data
            redisTemplate.delete("music:channel:" + channelId);
            redisTemplate.delete("music:channel:" + channelId + ":members");
            redisTemplate.delete("music:channel:" + channelId + ":queue");
            redisTemplate.delete("music:channel:" + channelId + ":state");
            redisTemplate.opsForSet().remove("music:channels:list", channelId);
            
            log.info("🗑️ Deleted channel: {}", channelId);
            return true;
            
        } catch (Exception e) {
            log.error("❌ Failed to delete channel: {}", e.getMessage());
            return false;
        }
    }

    // ============ MEMBER MANAGEMENT ============

    /**
     * Add member to channel
     */
    public void addMemberToChannel(String channelId, String userCode) {
        redisTemplate.opsForSet().add(
            "music:channel:" + channelId + ":members",
            userCode
        );
        
        log.info("👤 {} joined channel {}", userCode, channelId);
    }

    /**
     * Remove member from channel
     */
    public void removeMemberFromChannel(String channelId, String userCode) {
        redisTemplate.opsForSet().remove(
            "music:channel:" + channelId + ":members",
            userCode
        );
        
        log.info("👋 {} left channel {}", userCode, channelId);
    }

    /**
     * Get channel members
     */
    public Set<String> getChannelMembers(String channelId) {
        return redisTemplate.opsForSet().members("music:channel:" + channelId + ":members");
    }

    // ============ QUEUE MANAGEMENT ============

    /**
     * Add track to channel queue
     */
    public void addToQueue(String channelId, Map<String, Object> trackData) {
        try {
            String trackJson = objectMapper.writeValueAsString(trackData);
            
            redisTemplate.opsForList().rightPush(
                "music:channel:" + channelId + ":queue",
                trackJson
            );
            
            redisTemplate.expire(
                "music:channel:" + channelId + ":queue",
                CHANNEL_TTL_HOURS,
                TimeUnit.HOURS
            );
            
            log.info("➕ Added track to queue in channel {}", channelId);
            
        } catch (Exception e) {
            log.error("❌ Failed to add to queue: {}", e.getMessage());
        }
    }

    /**
     * Remove track from queue by index
     */
    public void removeFromQueue(String channelId, int index) {
        try {
            String queueKey = "music:channel:" + channelId + ":queue";
            
            // Get the track at index
            String track = redisTemplate.opsForList().index(queueKey, index);
            
            if (track != null) {
                // Remove it (Redis doesn't have direct remove by index)
                // Use a placeholder and then remove it
                redisTemplate.opsForList().set(queueKey, index, "___TO_REMOVE___");
                redisTemplate.opsForList().remove(queueKey, 1, "___TO_REMOVE___");
                
                log.info("➖ Removed track from queue at index {} in channel {}", index, channelId);
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to remove from queue: {}", e.getMessage());
        }
    }

    /**
     * Reorder queue
     */
    public void reorderQueue(String channelId, int oldIndex, int newIndex) {
        try {
            String queueKey = "music:channel:" + channelId + ":queue";
            
            // Get all tracks
            List<String> queue = redisTemplate.opsForList().range(queueKey, 0, -1);
            
            if (queue != null && oldIndex < queue.size() && newIndex < queue.size()) {
                // Reorder in memory
                String track = queue.remove(oldIndex);
                queue.add(newIndex, track);
                
                // Clear and rebuild queue
                redisTemplate.delete(queueKey);
                for (String t : queue) {
                    redisTemplate.opsForList().rightPush(queueKey, t);
                }
                
                redisTemplate.expire(queueKey, CHANNEL_TTL_HOURS, TimeUnit.HOURS);
                
                log.info("🔄 Reordered queue in channel {}", channelId);
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to reorder queue: {}", e.getMessage());
        }
    }

    /**
     * Get channel queue
     */
    public List<Map<String, Object>> getQueue(String channelId) {
        try {
            List<String> queueJson = redisTemplate.opsForList().range(
                "music:channel:" + channelId + ":queue",
                0,
                -1
            );
            
            if (queueJson == null) {
                return new ArrayList<>();
            }
            
            List<Map<String, Object>> queue = new ArrayList<>();
            for (String trackJson : queueJson) {
                Map<String, Object> track = objectMapper.readValue(
                    trackJson,
                    new TypeReference<Map<String, Object>>() {}
                );
                queue.add(track);
            }
            
            return queue;
            
        } catch (Exception e) {
            log.error("❌ Failed to get queue: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ============ PLAYBACK STATE ============

    /**
     * Update channel playback state
     */
    public void updateChannelState(String channelId, MusicSyncMessage message) {
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("action", message.getAction());
            state.put("timestamp", message.getTimestamp());
            
            if (message.getTrackData() != null) {
                state.put("currentTrack", message.getTrackData());
            }
            
            if (message.getPosition() != null) {
                state.put("position", message.getPosition());
            }
            
            String stateJson = objectMapper.writeValueAsString(state);
            
            redisTemplate.opsForValue().set(
                "music:channel:" + channelId + ":state",
                stateJson,
                CHANNEL_TTL_HOURS,
                TimeUnit.HOURS
            );
            
            log.debug("💾 Updated state for channel {}", channelId);
            
        } catch (Exception e) {
            log.error("❌ Failed to update channel state: {}", e.getMessage());
        }
    }

    /**
     * Get current channel state
     */
    public Map<String, Object> getChannelState(String channelId) {
        try {
            String stateJson = redisTemplate.opsForValue().get(
                "music:channel:" + channelId + ":state"
            );
            
            if (stateJson != null) {
                return objectMapper.readValue(
                    stateJson,
                    new TypeReference<Map<String, Object>>() {}
                );
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("❌ Failed to get channel state: {}", e.getMessage());
            return null;
        }
    }

    // ============ SPOTIFY AUTHENTICATION ============

    /**
     * Get Spotify access token (cached with auto-refresh)
     */
    public String getSpotifyAccessToken() {
        long now = System.currentTimeMillis();
        
        // Return cached token if still valid
        if (cachedSpotifyToken != null && now < spotifyTokenExpiry - 60000) {
            return cachedSpotifyToken;
        }
        
        try {
            String credentials = Base64.getEncoder().encodeToString(
                (spotifyClientId + ":" + spotifyClientSecret).getBytes(StandardCharsets.UTF_8)
            );
            
            RestTemplate restTemplate = new RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Basic " + credentials);
            headers.set("Content-Type", "application/x-www-form-urlencoded");
            
            org.springframework.http.HttpEntity<String> entity = 
                new org.springframework.http.HttpEntity<>("grant_type=client_credentials", headers);
            
            Map<String, Object> response = restTemplate.postForObject(
                "https://accounts.spotify.com/api/token",
                entity,
                Map.class
            );
            
            cachedSpotifyToken = (String) response.get("access_token");
            int expiresIn = (Integer) response.get("expires_in");
            spotifyTokenExpiry = now + (expiresIn * 1000L);
            
            log.info("✅ Spotify token refreshed");
            return cachedSpotifyToken;
            
        } catch (Exception e) {
            log.error("❌ Spotify authentication failed: {}", e.getMessage());
            throw new RuntimeException("Spotify authentication failed", e);
        }
    }

    /**
     * Get YouTube API key
     */
    public String getYouTubeApiKey() {
        if (youtubeApiKey == null || youtubeApiKey.isEmpty()) {
            throw new RuntimeException("YouTube API key not configured");
        }
        return youtubeApiKey;
    }
}
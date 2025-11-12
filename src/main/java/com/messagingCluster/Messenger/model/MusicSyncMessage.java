package com.messagingCluster.Messenger.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Music Sync Message Model
 * 
 * Used for WebSocket communication between clients for music synchronization
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MusicSyncMessage {
    
    // Message metadata
    private String type = "music_sync";
    private String action;
    private Long timestamp;
    
    // Channel info
    private String channelId;
    private String userCode;
    
    // Playback state
    private Map<String, Object> trackData;
    private Integer position;
    
    // Queue operations
    private Integer index;
    private Integer oldIndex;
    private Integer newIndex;
}
package com.messagingCluster.Messenger.model;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Data

public class MessageModel {

    private int id;
    private String sender;
    private String receiver;
    private String encryptedContent;
    private LocalDateTime timestamp = LocalDateTime.now();

}
package com.onelake.common.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationDTO(
    UUID id,
    String category,
    UUID receiverId,
    String title,
    String content,
    String link,
    String level,
    Boolean isRead,
    Instant createdAt
) {}

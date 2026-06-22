package com.onelake.common.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "CommonNotification")
@Table(name = "notification", schema = "common")
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID receiverId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 512)
    private String link;

    @Column(nullable = false, length = 16)
    private String level = "INFO";

    @Column(nullable = false, name = "is_read")
    private Boolean isRead = false;

    @Column(length = 64)
    private String sourceRefType;

    @Column(length = 128)
    private String sourceRefId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}

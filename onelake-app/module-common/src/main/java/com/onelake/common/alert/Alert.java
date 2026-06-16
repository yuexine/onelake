package com.onelake.common.alert;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity(name = "CommonAlert")
@Table(name = "alert", schema = "common")
@Getter @Setter
public class Alert {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false, length = 4) private String level;   // P0/P1/P2
    @Column(nullable = false, length = 32) private String source;
    @Column(nullable = false) private String title;
    private String rule;
    @Column(nullable = false) private String status = "OPEN";
    private String assignee;
    private UUID relatedRunId;
    private String relatedApi;
    private Instant createdAt = Instant.now();
    private Instant ackedAt;
}

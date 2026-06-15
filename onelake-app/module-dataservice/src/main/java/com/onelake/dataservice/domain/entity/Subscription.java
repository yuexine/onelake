package com.onelake.dataservice.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscription", schema = "dataservice")
@Getter @Setter
public class Subscription {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID apiId;
    @Column(nullable = false) private UUID subscriberId;
    private UUID appKeyId;
    @Column(nullable = false, length = 16) private String status = "PENDING";   // PENDING/APPROVED/REJECTED
    private UUID approvedBy;
    private Instant createdAt = Instant.now();
}

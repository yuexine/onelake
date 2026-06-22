package com.onelake.security.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approval_request", schema = "security")
@Getter @Setter
public class ApprovalRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false, length = 32) private String requestType;   // ACCESS/PUBLISH/OFFLINE
    @Column(nullable = false) private UUID applicantId;
    @Column(nullable = false) private String targetRef;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private String payload;
    @Column(nullable = false, length = 16) private String status = "PENDING";   // PENDING/APPROVED/REJECTED/CANCELED
    private UUID approverId;
    private String comment;
    private Instant createdAt = Instant.now();
    private Instant decidedAt;
}

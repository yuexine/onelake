package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.OperatorCategory;
import com.onelake.orchestration.domain.enums.OperatorScope;
import com.onelake.orchestration.domain.enums.OperatorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operator", schema = "orchestration")
@Getter
@Setter
public class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    @Column(nullable = false, length = 128)
    private String operatorRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OperatorCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OperatorScope scope;

    @Column(nullable = false, length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 24)
    private String latestVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OperatorStatus status = OperatorStatus.ACTIVE;

    private Instant createdAt = Instant.now();
}

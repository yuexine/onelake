package com.onelake.quality.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 质量规则（对应《技术初始化文档》§7.6 quality.rule）。
 */
@Entity
@Table(name = "rule", schema = "quality")
@Getter @Setter
public class Rule {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private String targetFqn;
    private String targetColumn;
    @Column(nullable = false, length = 32) private String ruleType;   // NOT_NULL/UNIQUE/RANGE/REGEX/CUSTOM_SQL
    @Column(nullable = false, columnDefinition = "text") private String expression;
    @Column(nullable = false, length = 16) private String severity = "BLOCK";   // BLOCK/WARN
    private UUID ownerId;
    private Boolean enabled = true;
    private Integer version = 1;
    @Column(length = 32)
    private String schedule = "ON_PARTITION";
    private Instant createdAt = Instant.now();
}

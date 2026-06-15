package com.onelake.security.domain.entity;

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

/**
 * PII 识别记录（对应前端 PiiScan 页面 mock 结构）。
 * 每行代表一个疑似敏感字段的扫描结果，status 为 pending → confirmed。
 */
@Entity
@Table(name = "pii_scan_record", schema = "security")
@Getter
@Setter
public class PiiScanRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String fqn;             // 库.表.字段

    @Column(nullable = false)
    private String piiType;         // 手机号/身份证/银行卡/邮箱/姓名

    @Column(nullable = false)
    private Double confidence;      // 0.0 ~ 1.0

    @Column(length = 4)
    private String suggestLevel;    // L3 / L4

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private Instant scannedAt = Instant.now();

    private Instant confirmedAt;

    public enum Status { PENDING, CONFIRMED, IGNORED }
}

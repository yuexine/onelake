package com.onelake.dataservice.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "quota_usage", schema = "dataservice")
@Getter @Setter
public class QuotaUsage {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID appKeyId;
    @Column(nullable = false) private UUID apiId;
    @Column(nullable = false) private LocalDate statDate;
    private Long callCount = 0L;
}

package com.onelake.dataservice.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "api_call_log", schema = "dataservice")
@Getter @Setter
public class ApiCallLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private java.util.UUID apiId;
    private java.util.UUID appKeyId;
    @Column(nullable = false) private Integer statusCode;
    private Integer latencyMs;
    private String requestIp;
    private Instant calledAt = Instant.now();
}

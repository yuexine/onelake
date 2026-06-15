package com.onelake.integration.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_source", schema = "integration")
@Getter @Setter
public class FileSource {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String sourceType;   // SFTP / FTP / NAS / S3
    @Column(nullable = false) private String endpoint;
    private String basePath;       // 监听目录
    @Column(length = 16) private String watchMode = "event"; // event / poll
    @Column(nullable = false) private Boolean enabled = true;
    private Instant createdAt = Instant.now();
}

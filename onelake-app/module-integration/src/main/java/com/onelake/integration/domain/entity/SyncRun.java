package com.onelake.integration.domain.entity;

import com.onelake.integration.domain.enums.RunStatus;
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
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * 同步运行实例（对应《技术初始化文档》§7.2 integration.sync_run）。
 */
@Entity
@Table(name = "sync_run", schema = "integration")
@Getter
@Setter
public class SyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID taskId;

    private String externalJobId;      // Airbyte/Flink job id

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status = RunStatus.RUNNING;

    private Long rowsRead = 0L;

    private Long rowsWritten = 0L;

    private String errorCode;

    @Column(columnDefinition = "text")
    private String errorMsg;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String checkpoint;

    private Instant startedAt = Instant.now();
    private Instant finishedAt;
}

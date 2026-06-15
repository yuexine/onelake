package com.onelake.integration.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Schema 快照传输对象。
 */
@Data
@Builder
public class SourceSchemaSnapshotDTO {
    private UUID id;
    private UUID sourceId;
    private String objectName;       // 库.表
    private String columns;          // JSON
    private String checksum;
    private Instant capturedAt;
}

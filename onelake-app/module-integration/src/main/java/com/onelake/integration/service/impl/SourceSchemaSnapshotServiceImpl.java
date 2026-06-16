package com.onelake.integration.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.integration.client.discovery.DatabaseDiscoveryClient;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.domain.entity.SourceSchemaSnapshot;
import com.onelake.integration.dto.DiscoveredColumnDTO;
import com.onelake.integration.dto.SourceSchemaSnapshotDTO;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.repository.SourceSchemaSnapshotRepository;
import com.onelake.integration.service.SourceSchemaSnapshotService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Schema 快照与漂移检测服务实现。
 *
 * <p>当前从 {@code DatabaseDiscoveryClient} 拉取 MySQL/Postgres 真实 columns。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceSchemaSnapshotServiceImpl implements SourceSchemaSnapshotService {

    private final SourceSchemaSnapshotRepository repo;
    private final DataSourceRepository dataSourceRepo;
    private final DatabaseDiscoveryClient discoveryClient;
    private final AuditLogger audit;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public SourceSchemaSnapshotDTO capture(UUID sourceId, String objectName) {
        if (sourceId == null || objectName == null || objectName.isBlank()) {
            throw new BizException(40000, "sourceId 和 objectName 不能为空");
        }
        String columnsJson = captureColumns(sourceId, objectName);
        String checksum = md5(columnsJson);

        Optional<SourceSchemaSnapshot> last = repo.findFirstBySourceIdAndObjectNameOrderByCapturedAtDesc(sourceId, objectName);
        if (last.isPresent() && checksum.equals(last.get().getChecksum())) {
            // 与上次相同，不写新行；返回上次
            audit.audit("SCHEMA_SNAPSHOT_NOOP", "source_schema_snapshot",
                    last.get().getId() == null ? null : last.get().getId().toString(),
                    "Snapshot unchanged for " + objectName);
            return toDto(last.get());
        }

        SourceSchemaSnapshot snap = new SourceSchemaSnapshot();
        snap.setSourceId(sourceId);
        snap.setObjectName(objectName);
        snap.setColumns(columnsJson);
        snap.setChecksum(checksum);
        snap.setCapturedAt(Instant.now());
        SourceSchemaSnapshot saved = repo.save(snap);

        audit.audit("SCHEMA_SNAPSHOT_CAPTURED", "source_schema_snapshot",
                saved.getId() == null ? null : saved.getId().toString(),
                "Captured snapshot for " + objectName);
        last.ifPresent(previous -> publishDriftIfChanged(sourceId, objectName, previous, saved));
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceSchemaSnapshotDTO> listBySource(UUID sourceId) {
        return repo.findBySourceIdOrderByCapturedAtDesc(sourceId).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceSchemaSnapshotDTO> listByObject(UUID sourceId, String objectName) {
        return repo.findBySourceIdOrderByCapturedAtDesc(sourceId).stream()
                .filter(s -> objectName == null || objectName.equals(s.getObjectName()))
                .sorted(Comparator.comparing(SourceSchemaSnapshot::getCapturedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DriftResult detectDrift(UUID sourceId, String objectName) {
        List<SourceSchemaSnapshot> history = repo.findBySourceIdOrderByCapturedAtDesc(sourceId).stream()
                .filter(s -> objectName.equals(s.getObjectName()))
                .sorted(Comparator.comparing(SourceSchemaSnapshot::getCapturedAt).reversed())
                .toList();
        if (history.size() < 2) {
            return new DriftResult(objectName, false,
                    history.isEmpty() ? null : history.get(0).getChecksum(),
                    history.isEmpty() ? null : history.get(0).getChecksum(),
                    List.of());
        }
        SourceSchemaSnapshot curr = history.get(0);
        SourceSchemaSnapshot prev = history.get(1);
        if (curr.getChecksum().equals(prev.getChecksum())) {
            return new DriftResult(objectName, false, prev.getChecksum(), curr.getChecksum(), List.of());
        }
        return new DriftResult(objectName, true, prev.getChecksum(), curr.getChecksum(),
                diffColumns(prev.getColumns(), curr.getColumns()));
    }

    /* ---------------- helpers ---------------- */

    private String captureColumns(UUID sourceId, String objectName) {
        DataSource ds = dataSourceRepo.findById(sourceId)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(ds.getTenantId())) {
            throw new BizException(40400, "数据源不存在");
        }
        List<DiscoveredColumnDTO> columns = discoveryClient.describeTable(ds.getType(), configMap(ds), objectName);
        return JsonUtil.toJson(columns);
    }

    private SourceSchemaSnapshotDTO toDto(SourceSchemaSnapshot s) {
        return SourceSchemaSnapshotDTO.builder()
                .id(s.getId())
                .sourceId(s.getSourceId())
                .objectName(s.getObjectName())
                .columns(s.getColumns())
                .checksum(s.getChecksum())
                .capturedAt(s.getCapturedAt())
                .build();
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BizException(50000, "MD5 计算失败: " + e.getMessage());
        }
    }

    /** 简单的 columns JSON diff：按 name 索引，检测 ADD / REMOVE / TYPE_CHANGE。 */
    private List<ColumnChange> diffColumns(String prevJson, String currJson) {
        List<ColumnChange> changes = new ArrayList<>();
        try {
            JsonNode prev = objectMapper.readTree(prevJson);
            JsonNode curr = objectMapper.readTree(currJson);
            java.util.Map<String, String> prevMap = columnsToMap(prev);
            java.util.Map<String, String> currMap = columnsToMap(curr);

            Set<String> all = new HashSet<>();
            all.addAll(prevMap.keySet());
            all.addAll(currMap.keySet());

            for (String name : all) {
                String p = prevMap.get(name);
                String c = currMap.get(name);
                if (p == null) {
                    changes.add(new ColumnChange(name, "ADD", null, c));
                } else if (c == null) {
                    changes.add(new ColumnChange(name, "REMOVE", p, null));
                } else if (!p.equals(c)) {
                    changes.add(new ColumnChange(name, "TYPE_CHANGE", p, c));
                }
            }
        } catch (Exception e) {
            log.warn("diffColumns failed, returning empty changes", e);
        }
        return changes;
    }

    private void publishDriftIfChanged(
            UUID sourceId,
            String objectName,
            SourceSchemaSnapshot previous,
            SourceSchemaSnapshot current
    ) {
        if (previous.getChecksum().equals(current.getChecksum())) {
            return;
        }
        List<ColumnChange> changes = diffColumns(previous.getColumns(), current.getColumns());
        outbox.publish(DomainEvents.INTEGRATION_SCHEMA_DRIFT, current.getId().toString(), Map.of(
            "sourceId", sourceId.toString(),
            "object", objectName,
            "previousChecksum", previous.getChecksum(),
            "currentChecksum", current.getChecksum(),
            "changes", changes
        ));
    }

    private java.util.Map<String, String> columnsToMap(JsonNode arr) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (arr == null || !arr.isArray()) return map;
        Iterator<JsonNode> it = arr.elements();
        while (it.hasNext()) {
            JsonNode n = it.next();
            String name = n.path("name").asText("");
            String type = n.path("type").asText("");
            if (!name.isEmpty()) map.put(name, type);
        }
        return map;
    }

    private Map<String, Object> configMap(DataSource ds) {
        try {
            return JsonUtil.mapper().readValue(ds.getConfig(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BizException(50001, "数据源配置解析失败");
        }
    }
}

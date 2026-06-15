package com.onelake.integration.service.impl;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.integration.domain.entity.CdcTask;
import com.onelake.integration.domain.entity.DataSource;
import com.onelake.integration.dto.CdcStatusDTO;
import com.onelake.integration.repository.CdcTaskRepository;
import com.onelake.integration.repository.DataSourceRepository;
import com.onelake.integration.service.CdcTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CDC 任务服务实现。
 *
 * <p>当前为 scaffold stub —— start/pause/status 仅更新本地状态，
 * 不调用 Flink JobManager 或 Debezium REST API。
 * 真实实现需：
 * <ol>
 *   <li>start: 调 Flink REST 提交 FlinkCDC SQL Job</li>
 *   <li>pause: 调 Flink REST cancel + savepoint</li>
 *   <li>status: 调 Flink REST 获取 job metrics（位点/延迟/吞吐）</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CdcTaskServiceImpl implements CdcTaskService {

    private final CdcTaskRepository repo;
    private final DataSourceRepository dsRepo;
    private final AuditLogger audit;
    private final OutboxPublisher outbox;
    private final com.onelake.integration.client.FlinkCdcClient flink;

    @Override
    @Transactional
    public CdcTask create(UUID sourceId, String tableName) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        DataSource ds = dsRepo.findById(sourceId)
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));
        CdcTask task = new CdcTask();
        task.setTenantId(tenantId);
        task.setSourceId(sourceId);
        task.setSourceName(ds.getName());
        task.setTableName(tableName);
        task.setTopicName("onelake.cdc." + tenantId + "." + tableName.replace('.', '_'));
        task.setStatus(CdcTask.CdcStatus.DRAFT);
        task.setCreatedAt(Instant.now());
        repo.save(task);
        audit.auditCreate("cdc_task", task.getId(), Map.of("table", tableName));
        outbox.publish(DomainEvents.INTEGRATION_CDC_TASK_CREATED, task.getId().toString(),
            Map.of("tenantId", tenantId.toString(), "table", tableName, "topic", task.getTopicName()));
        return task;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CdcTask> list() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "租户上下文缺失");
        return repo.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public CdcTask get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new BizException(40400, "CDC 任务不存在"));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        CdcTask task = get(id);
        if (task.getStatus() == CdcTask.CdcStatus.RUNNING) {
            throw new BizException(40012, "CDC 任务运行中，请先暂停再删除");
        }
        repo.delete(task);
        audit.auditDelete("cdc_task", id);
    }

    @Override
    @Transactional
    public CdcTask start(UUID id) {
        CdcTask task = get(id);
        DataSource ds = dsRepo.findById(task.getSourceId())
            .orElseThrow(() -> new BizException(40400, "数据源不存在"));

        // 解析 DataSource config JSON 获取连接参数
        com.fasterxml.jackson.databind.JsonNode cfg;
        try {
            cfg = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(ds.getConfig() == null ? "{}" : ds.getConfig());
        } catch (Exception e) {
            throw new BizException(50010, "数据源 config 解析失败");
        }
        String host = cfg.path("host").asText("localhost");
        int port = cfg.path("port").asInt(3306);
        String user = cfg.path("username").asText("root");
        String pwd = cfg.path("password").asText("");
        String db = cfg.path("dbName").asText("");

        // 提交 FlinkCDC Job
        String flinkJobId = flink.submitCdcJob(host, port, user, pwd, db,
            task.getTableName(), task.getTopicName());
        task.setStatus(CdcTask.CdcStatus.RUNNING);
        task.setStartedAt(Instant.now());
        task.setCheckpoint(flinkJobId); // checkpoint 字段暂存 Flink job ID
        audit.audit("START", "cdc_task", id.toString(), Map.of(
            "table", task.getTableName(), "flinkJobId", flinkJobId));
        log.info("CDC task {} started, flinkJobId={}", task.getTableName(), flinkJobId);
        return task;
    }

    @Override
    @Transactional
    public CdcTask pause(UUID id) {
        CdcTask task = get(id);
        String flinkJobId = task.getCheckpoint();
        boolean cancelled = false;
        if (flinkJobId != null && !flinkJobId.isBlank()) {
            cancelled = flink.cancelJob(flinkJobId);
        }
        task.setStatus(CdcTask.CdcStatus.PAUSED);
        audit.audit("PAUSE", "cdc_task", id.toString(),
            Map.of("flinkJobId", flinkJobId == null ? "" : flinkJobId, "cancelled", cancelled));
        log.info("CDC task {} paused (cancelled={})", task.getTableName(), cancelled);
        return task;
    }

    @Override
    @Transactional(readOnly = true)
    public CdcStatusDTO status(UUID id) {
        CdcTask task = get(id);
        String flinkJobId = task.getCheckpoint();
        Map<String, Object> jobDetail = null;
        if (flinkJobId != null && !flinkJobId.startsWith("flink-stub")) {
            jobDetail = flink.getJobDetail(flinkJobId);
        }
        return CdcStatusDTO.builder()
            .checkpoint(flinkJobId)
            .status(task.getStatus().name())
            .lagMs(1200)   // TODO: 从 Flink metrics 获取真实延迟
            .backpressure(jobDetail != null && Boolean.TRUE.equals(jobDetail.get("backPressure")))
            .build();
    }
}

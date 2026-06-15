package com.onelake.dataservice.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.DomainEventHandler;
import com.onelake.common.outbox.OutboxEvent;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * 消费 integration.sync_task.created 事件，自动为新采集表注册一个 DRAFT API 定义。
 *
 * <p>设计意图（CLAUDE.md §3 旅程一闭环）：
 * 采集任务创建 → 数据将落入 ODS → 自动预生成 API 定义（DRAFT），
 * 数据工程师审核后一键发布即可对外提供查询服务。
 *
 * <p>生成的 API：
 * <ul>
 *   <li>路径：/api/&lt;表名最后一段&gt;</li>
 *   <li>SQL：SELECT * FROM &lt;targetTable&gt; LIMIT 100</li>
 *   <li>状态：DRAFT（需人工审核后发布）</li>
 * </ul>
 *
 * <p>幂等：如果该 apiPath 已存在则跳过，不重复创建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncTaskCreatedApiHandler implements DomainEventHandler {

    private final ApiDefinitionRepository apiRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Set<String> eventTypes() {
        return Set.of(DomainEvents.INTEGRATION_SYNC_TASK_CREATED);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            String targetTable = p.path("targetTable").asText("");
            String tenantIdRaw = p.path("tenantId").asText("");

            if (targetTable.isBlank() || tenantIdRaw.isBlank()) {
                log.warn("SyncTaskCreatedApiHandler: missing targetTable/tenantId");
                return;
            }

            // 从 ods.orders 提取 "orders" 作为 API 路径后缀
            String[] parts = targetTable.split("\\.");
            String shortName = parts.length > 0 ? parts[parts.length - 1] : targetTable;
            String apiPath = "/api/" + shortName;

            // 幂等：已存在则跳过
            if (apiRepo.findByApiPath(apiPath).isPresent()) {
                log.debug("SyncTaskCreatedApiHandler: API {} already exists, skipping", apiPath);
                return;
            }

            ApiDefinition api = new ApiDefinition();
            api.setTenantId(UUID.fromString(tenantIdRaw));
            api.setApiPath(apiPath);
            api.setViewName("v_" + shortName);
            api.setSelectSql("SELECT * FROM " + targetTable + " LIMIT 100");
            api.setSourceFqn(targetTable);
            api.setQpsLimit(20);
            api.setStatus("DRAFT");
            api.setCurrentVersion(1);
            apiRepo.save(api);

            log.info("SyncTaskCreatedApiHandler: auto-created DRAFT API {} for table {}", apiPath, targetTable);
        } catch (Exception e) {
            log.error("SyncTaskCreatedApiHandler failed for event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}

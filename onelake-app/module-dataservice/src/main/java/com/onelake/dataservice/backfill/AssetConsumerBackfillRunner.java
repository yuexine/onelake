package com.onelake.dataservice.backfill;

import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.dataservice.domain.entity.ApiDefinition;
import com.onelake.dataservice.repository.ApiDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据服务模块的影响分析投影表回填脚本（对应《血缘图模块完善设计方案》§6 阶段 A'₂）。
 *
 * <p>启动方式（独立 PR）：
 * <pre>
 * java -jar bootstrap.jar --spring.profiles.active=backfill
 * # 或限定租户
 * java -jar bootstrap.jar --spring.profiles.active=backfill --tenant-id=11111111-1111-1111-1111-111111111111
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>只在 {@code backfill} profile 激活时运行，正常启动无副作用。</li>
 *   <li>扫描所有 PUBLISHED 状态的 API，发 {@code dataservice.api.published} 事件。</li>
 *   <li>catalog 的 {@code AssetConsumerEventHandler} 消费后写入 {@code catalog.asset_consumer} 投影表。</li>
 *   <li>支持 {@code --tenant-id} 参数限定单租户；支持 {@code --dry-run} 仅打印不发出。</li>
 *   <li>不直接写 catalog 表（架构铁律：模块不跨 schema 直读直写）。</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("backfill")
@RequiredArgsConstructor
public class AssetConsumerBackfillRunner implements ApplicationRunner {

    private final ApiDefinitionRepository apiRepo;
    private final OutboxPublisher outbox;

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = args.containsOption("dry-run");
        String tenantFilter = firstOption(args, "tenant-id");

        log.info("AssetConsumerBackfill start (dryRun={}, tenantFilter={})", dryRun, tenantFilter);

        List<ApiDefinition> apis = apiRepo.findAll();
        int total = 0;
        int skipped = 0;
        int published = 0;
        int errored = 0;

        for (ApiDefinition api : apis) {
            total++;
            if (tenantFilter != null && !tenantFilter.equals(String.valueOf(api.getTenantId()))) {
                skipped++;
                continue;
            }
            if (!"PUBLISHED".equalsIgnoreCase(api.getStatus())) {
                skipped++;
                continue;
            }
            if (api.getSourceFqn() == null || api.getSourceFqn().isBlank()) {
                skipped++;
                continue;
            }
            try {
                if (!dryRun) {
                    // 在回填场景下：JPA 加载的实体可能没绑定 TenantContext，显式设入
                    TenantContext.setTenantId(api.getTenantId());
                    outbox.publish(
                        DomainEvents.DATASERVICE_API_PUBLISHED,
                        api.getId().toString(),
                        buildPayload(api)
                    );
                }
                published++;
                if (published % 50 == 0) {
                    log.info("AssetConsumerBackfill progress: {} published", published);
                }
            } catch (Exception e) {
                errored++;
                log.warn("AssetConsumerBackfill failed for api {}: {}", api.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }

        log.info("AssetConsumerBackfill done: total={} skipped={} published={} errored={}",
            total, skipped, dryRun ? 0 : published, errored);
        if (dryRun) {
            log.info("dry-run mode: {} events would have been published", published);
        }
    }

    private Map<String, Object> buildPayload(ApiDefinition api) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", String.valueOf(api.getTenantId()));
        payload.put("assetFqn", api.getSourceFqn());
        payload.put("consumerType", "API");
        payload.put("consumerRef", String.valueOf(api.getId()));
        payload.put("consumerName", api.getApiPath() == null ? api.getViewName() : api.getApiPath());
        payload.put("ownerName", TenantContext.getUsername());
        payload.put("action", "UPSERT");
        return payload;
    }

    private String firstOption(ApplicationArguments args, String key) {
        List<String> values = args.getOptionValues(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}

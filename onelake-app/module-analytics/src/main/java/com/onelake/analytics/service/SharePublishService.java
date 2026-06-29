package com.onelake.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onelake.analytics.api.vo.DashboardPublishRequest;
import com.onelake.analytics.domain.entity.Dashboard;
import com.onelake.analytics.domain.entity.DashboardPublication;
import com.onelake.analytics.repository.DatasetRepository;
import com.onelake.analytics.repository.DashboardPublicationRepository;
import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 大屏发布与分享服务（§5.3、§7.7 v1.1）。
 *
 * 关键：
 * 1. 发布生成 dashboard_publication 快照 + is_current 唯一约束（每次发布前 clearCurrent）
 * 2. 公开分享（isPublic=true）对数据集 row_filter 做硬校验
 * 3. 公开通道生成 shareToken（32 字节随机）
 * 4. Outbox 发 analytics.dashboard.published 事件 → module-catalog 回写血缘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePublishService {

    private final DashboardService dashboardService;
    private final DatasetRepository datasetRepo;
    private final DashboardPublicationRepository pubRepo;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public DashboardPublication publish(UUID dashboardId, DashboardPublishRequest req) {
        Dashboard d = dashboardService.get(dashboardId);

        // 公开分享：必须校验所有绑定数据集的 row_filter 为空
        if (Boolean.TRUE.equals(req.getIsPublic())) {
            verifyNoRowFilterForPublicShare(d);
        }

        // 1) 清空同 dashboard 的 is_current 标记
        pubRepo.clearCurrentForDashboard(dashboardId);

        // 2) 构建快照（canvas + spec + 全局变量）
        ObjectNode snapshot = JsonUtil.mapper().createObjectNode();
        snapshot.set("canvas", JsonUtil.parse(d.getCanvas()));
        snapshot.set("spec", JsonUtil.parse(d.getSpec()));

        // 3) 插入新 publication
        DashboardPublication pub = new DashboardPublication();
        pub.setDashboardId(dashboardId);
        pub.setTenantId(d.getTenantId());
        pub.setVersion(nextVersion(dashboardId));
        pub.setSnapshot(snapshot.toString());
        if (Boolean.TRUE.equals(req.getIsPublic())) {
            pub.setIsPublic(true);
            pub.setShareToken(generateToken(32));
        }
        pub.setIsCurrent(true);
        pub.setExpireAt(req.getExpireAt());
        pub.setPublishedBy(TenantContext.getUserId());
        pub = pubRepo.save(pub);

        // 4) 更新 dashboard 冗余字段 + Outbox 事件（与 catalog 血缘回写联动）
        d.setCurrentPublicationId(pub.getId());
        d.setUpdatedAt(Instant.now());
        dashboardService.save(dashboardId, new com.onelake.analytics.api.vo.DashboardSaveRequest());

        outbox.publish(DomainEvents.ANALYTICS_DASHBOARD_PUBLISHED, dashboardId.toString(),
            Map.of("version", pub.getVersion(), "isPublic", pub.getIsPublic(),
                   "tenantId", String.valueOf(d.getTenantId())));
        audit.auditUpdate("analytics.dashboard", dashboardId,
            "publish v" + pub.getVersion() + " public=" + pub.getIsPublic());
        return pub;
    }

    /**
     * 通过 shareToken 拉公开快照（无需鉴权）。
     */
    @Transactional(readOnly = true)
    public DashboardPublication getByShareToken(String token) {
        DashboardPublication pub = pubRepo.findByShareToken(token)
            .orElseThrow(() -> new BizException(40400, "分享链接无效或已撤销"));
        if (pub.getExpireAt() != null && pub.getExpireAt().isBefore(Instant.now())) {
            throw new BizException(40400, "分享链接已过期");
        }
        if (!Boolean.TRUE.equals(pub.getIsPublic())) {
            throw new BizException(40400, "分享链接未开启公开");
        }
        return pub;
    }

    /**
     * 校验：公开分享的所有绑定数据集必须 row_filter 为空（§5.3 硬约束）。
     */
    private void verifyNoRowFilterForPublicShare(Dashboard d) {
        JsonNode spec = JsonUtil.parse(d.getSpec());
        JsonNode widgets = spec.path("widgets");
        if (!widgets.isArray()) return;

        for (JsonNode w : widgets) {
            JsonNode data = w.path("data");
            if (data.isMissingNode() || data.path("datasetId").isMissingNode()) continue;
            UUID datasetId = UUID.fromString(data.get("datasetId").asText());
            datasetRepo.findById(datasetId).ifPresent(ds -> {
                if (ds.getRowFilter() != null && !ds.getRowFilter().isBlank()) {
                    throw new BizException(40003,
                        "公开分享不允许数据集带 row_filter，" + ds.getName() + " 违规");
                }
            });
        }
    }

    private int nextVersion(UUID dashboardId) {
        return pubRepo.findByDashboardIdAndIsCurrentTrue(dashboardId)
            .map(p -> p.getVersion() + 1).orElse(1);
    }

    private static String generateToken(int byteLen) {
        byte[] buf = new byte[byteLen];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(byteLen * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

package com.onelake.common.notification;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.task.RunningTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> PIPELINE_LEVELS = Set.of("INFO", "WARNING", "CRITICAL");

    private final NotificationRepository repo;

    @Transactional(readOnly = true)
    public List<NotificationDTO> list(int requestedLimit) {
        UUID tenantId = requireTenant();
        UUID receiverId = requireUser();
        int limit = normalizeLimit(requestedLimit);
        return repo.findByTenantIdAndReceiverIdOrderByCreatedAtDesc(
                tenantId,
                receiverId,
                PageRequest.of(0, limit)
            )
            .stream()
            .map(this::toDTO)
            .toList();
    }

    @Transactional
    public NotificationDTO markRead(UUID id) {
        UUID tenantId = requireTenant();
        UUID receiverId = requireUser();
        Notification notification = repo.findByTenantIdAndReceiverIdAndId(tenantId, receiverId, id)
            .orElseThrow(() -> new BizException(40400, "通知不存在"));
        notification.setIsRead(true);
        return toDTO(repo.save(notification));
    }

    @Transactional
    public void markAllRead() {
        UUID tenantId = requireTenant();
        UUID receiverId = requireUser();
        repo.findByTenantIdAndReceiverIdAndIsReadFalse(tenantId, receiverId)
            .forEach(notification -> notification.setIsRead(true));
    }

    @Transactional
    public void notifyTaskIfNeeded(RunningTask task) {
        if (task == null || !"FAILED".equals(task.getStatus())) {
            return;
        }
        UUID tenantId = task.getTenantId();
        UUID receiverId = task.getUserId() == null ? TenantContext.getUserId() : task.getUserId();
        if (tenantId == null || receiverId == null || task.getRefType() == null || task.getRefId() == null) {
            return;
        }
        boolean exists = repo.findByTenantIdAndReceiverIdAndSourceRefTypeAndSourceRefId(
            tenantId,
            receiverId,
            task.getRefType(),
            task.getRefId()
        ).isPresent();
        if (exists) {
            return;
        }
        try {
            repo.save(fromFailedTask(task, receiverId));
        } catch (DataIntegrityViolationException e) {
            log.debug("skip duplicated task notification for {}:{}", task.getRefType(), task.getRefId());
        }
    }

    /**
     * Persist a notification requested by an orchestration NOTIFY node.
     *
     * <p>The caller supplies an explicit tenant and receiver because Dagster invokes the
     * orchestration internal API without a user JWT. The source key makes retries idempotent
     * through {@code uk_notification_receiver_source}.</p>
     *
     * @return {@code true} when a new notification was inserted, {@code false} for a retry
     *         that already produced the same notification
     */
    @Transactional
    public boolean notifyPipelineNode(UUID tenantId,
                                      UUID receiverId,
                                      String title,
                                      String content,
                                      String link,
                                      String level,
                                      String sourceRefId) {
        if (tenantId == null || receiverId == null) {
            throw new BizException(40020, "通知租户与接收人不能为空");
        }
        String normalizedTitle = title == null ? "" : title.trim();
        String normalizedContent = content == null ? "" : content;
        String normalizedLevel = level == null ? "INFO" : level.trim().toUpperCase(Locale.ROOT);
        if (normalizedTitle.isEmpty() || normalizedTitle.length() > 256) {
            throw new BizException(40020, "通知标题不能为空且不能超过 256 字符");
        }
        if (!PIPELINE_LEVELS.contains(normalizedLevel)) {
            throw new BizException(40020, "通知级别必须是 INFO、WARNING 或 CRITICAL");
        }
        if (sourceRefId == null || sourceRefId.isBlank() || sourceRefId.length() > 128) {
            throw new BizException(40020, "通知幂等键不能为空且不能超过 128 字符");
        }
        return repo.insertPipelineNodeNotification(
                UUID.randomUUID(), tenantId, receiverId, normalizedTitle, normalizedContent,
                link == null || link.isBlank() ? null : link.trim(), normalizedLevel,
                sourceRefId, Instant.now()) == 1;
    }

    /**
     * 影响分析通知（对应《血缘图模块完善设计方案》§5.2.3 通知渠道）。
     *
     * <p>去重：依赖 {@code uk_notification_receiver_source} 唯一索引
     * (tenant_id, receiver_id, source_ref_type, source_ref_id)。
     * 同一 receiver 对同一 rootFqn 永远只收一条，配合 sourceRefId=rootFqn 实现「24h 去重」更严格的等价语义。
     *
     * @param receiverId 通知接收人
     * @param rootFqn    影响分析根资产
     * @param severity   HIGH/MEDIUM/LOW
     * @param summary    摘要文本（含受影响数量）
     * @return true 表示新建通知；false 表示重复被去重
     */
    @Transactional
    public boolean notifyImpactAnalysis(UUID receiverId, String rootFqn, String severity, String summary) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null || receiverId == null || rootFqn == null || rootFqn.isBlank()) {
            return false;
        }
        boolean exists = repo.findByTenantIdAndReceiverIdAndSourceRefTypeAndSourceRefId(
            tenantId, receiverId, "IMPACT_ANALYSIS", rootFqn
        ).isPresent();
        if (exists) return false;
        try {
            repo.save(fromImpactAnalysis(tenantId, receiverId, rootFqn, severity, summary));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("skip duplicated impact notification for {}", rootFqn);
            return false;
        }
    }

    private Notification fromImpactAnalysis(UUID tenantId, UUID receiverId, String rootFqn,
                                            String severity, String summary) {
        Notification n = new Notification();
        n.setTenantId(tenantId);
        n.setReceiverId(receiverId);
        n.setCategory("IMPACT_ANALYSIS");
        n.setTitle("影响分析：" + rootFqn + "（" + severity + "）");
        n.setContent(summary);
        n.setLink("/catalog/lineage?fqn=" + rootFqn);
        n.setLevel("HIGH".equals(severity) ? "CRITICAL" : "MEDIUM".equals(severity) ? "WARNING" : "INFO");
        n.setIsRead(false);
        n.setSourceRefType("IMPACT_ANALYSIS");
        n.setSourceRefId(rootFqn);
        n.setCreatedAt(Instant.now());
        return n;
    }

    private Notification fromFailedTask(RunningTask task, UUID receiverId) {
        Notification notification = new Notification();
        notification.setTenantId(task.getTenantId());
        notification.setReceiverId(receiverId);
        notification.setCategory("TASK");
        notification.setTitle("任务失败：" + task.getTitle());
        notification.setContent(task.getErrorMessage() == null || task.getErrorMessage().isBlank()
            ? task.getDetail()
            : task.getErrorMessage());
        notification.setLink(task.getLink());
        notification.setLevel("CRITICAL");
        notification.setIsRead(false);
        notification.setSourceRefType(task.getRefType());
        notification.setSourceRefId(task.getRefId());
        notification.setCreatedAt(Instant.now());
        return notification;
    }

    private NotificationDTO toDTO(Notification n) {
        return new NotificationDTO(
            n.getId(),
            n.getCategory(),
            n.getReceiverId(),
            n.getTitle(),
            n.getContent(),
            n.getLink(),
            n.getLevel(),
            n.getIsRead(),
            n.getCreatedAt()
        );
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "缺少租户上下文");
        }
        return tenantId;
    }

    private UUID requireUser() {
        UUID userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(40101, "缺少用户上下文");
        }
        return userId;
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}

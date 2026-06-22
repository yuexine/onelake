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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

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

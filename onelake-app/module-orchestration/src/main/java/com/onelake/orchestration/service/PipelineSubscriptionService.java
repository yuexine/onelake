package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineSubscription;
import com.onelake.orchestration.dto.PipelineSubscriptionDTO;
import com.onelake.orchestration.dto.PipelineSubscriptionRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 流水线自动化订阅的租户级查询、新增与删除服务。 */
@Service
@RequiredArgsConstructor
public class PipelineSubscriptionService {

    private static final Set<String> SOURCE_TYPES = Set.of("ASSET", "PIPELINE");
    private static final Set<String> CONDITIONS = Set.of(
            "ON_UPDATE", "ON_UPDATE_AND_QUALITY_PASS");
    private static final Set<String> FRESHNESS_POLICIES = Set.of(
            "LATEST", "SAME_BATCH", "SAME_FRESHNESS_WINDOW");
    private static final int SOURCE_REF_MAX_LENGTH = 512;

    private final PipelineSubscriptionRepository subscriptionRepo;
    private final DagRepository dagRepo;

    /** 按创建顺序列出当前租户指定下游流水线的全部自动化订阅。 */
    @Transactional(readOnly = true)
    public List<PipelineSubscriptionDTO> list(UUID dagId) {
        UUID tenantId = requireTenant();
        requireDag(dagId, tenantId);
        return subscriptionRepo.findByDagIdAndTenantIdOrderByCreatedAtAsc(dagId, tenantId)
                .stream()
                .map(PipelineSubscriptionDTO::of)
                .toList();
    }

    /** 新增并启用一条经规范化与租户归属校验的自动化订阅。 */
    @Transactional
    public PipelineSubscriptionDTO create(UUID dagId, PipelineSubscriptionRequest request) {
        UUID tenantId = requireTenant();
        Dag downstream = requireDag(dagId, tenantId);
        NormalizedSubscription normalized = normalize(request);
        if ("PIPELINE".equals(normalized.sourceType())) {
            UUID upstreamId = parsePipelineId(normalized.sourceRef());
            Dag upstream = requireDag(upstreamId, tenantId);
            if (downstream.getId().equals(upstream.getId())) {
                throw new BizException(40084, "流水线不能订阅自身");
            }
            normalized = new NormalizedSubscription(
                    normalized.sourceType(), upstream.getId().toString(),
                    normalized.condition(), normalized.freshnessPolicy());
        }

        if (subscriptionRepo.existsByDagIdAndSourceTypeAndSourceRef(
                dagId, normalized.sourceType(), normalized.sourceRef())) {
            throw new BizException(40904, "相同来源的自动化订阅已存在");
        }

        PipelineSubscription subscription = new PipelineSubscription();
        subscription.setTenantId(tenantId);
        subscription.setDagId(dagId);
        subscription.setSourceType(normalized.sourceType());
        subscription.setSourceRef(normalized.sourceRef());
        subscription.setCondition(normalized.condition());
        subscription.setFreshnessPolicy(normalized.freshnessPolicy());
        subscription.setEnabled(true);
        try {
            return PipelineSubscriptionDTO.of(subscriptionRepo.saveAndFlush(subscription));
        } catch (DataIntegrityViolationException ex) {
            throw new BizException(40904, "相同来源的自动化订阅已存在", ex);
        }
    }

    /** 删除当前租户指定下游流水线的一条自动化订阅。 */
    @Transactional
    public void delete(UUID dagId, UUID subscriptionId) {
        UUID tenantId = requireTenant();
        requireDag(dagId, tenantId);
        PipelineSubscription subscription = subscriptionRepo
                .findByIdAndDagIdAndTenantId(subscriptionId, dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "自动化订阅不存在"));
        subscriptionRepo.delete(subscription);
    }

    private NormalizedSubscription normalize(PipelineSubscriptionRequest request) {
        if (request == null) {
            throw new BizException(40080, "订阅配置不能为空");
        }
        String sourceType = normalizeEnum(request.sourceType());
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new BizException(40081, "sourceType 仅支持 ASSET 或 PIPELINE");
        }
        if (!StringUtils.hasText(request.sourceRef())) {
            throw new BizException(40082, "sourceRef 不能为空");
        }
        String sourceRef = request.sourceRef().trim();
        if (sourceRef.length() > SOURCE_REF_MAX_LENGTH) {
            throw new BizException(40082, "sourceRef 长度不能超过 512");
        }
        String condition = normalizeEnum(request.condition());
        if (!CONDITIONS.contains(condition)) {
            throw new BizException(40083,
                    "condition 仅支持 ON_UPDATE 或 ON_UPDATE_AND_QUALITY_PASS");
        }
        String freshnessPolicy = normalizeEnum(request.freshnessPolicy());
        if (!FRESHNESS_POLICIES.contains(freshnessPolicy)) {
            throw new BizException(40085,
                    "freshnessPolicy 仅支持 LATEST、SAME_BATCH 或 SAME_FRESHNESS_WINDOW");
        }
        return new NormalizedSubscription(
                sourceType, sourceRef, condition, freshnessPolicy);
    }

    private UUID parsePipelineId(String sourceRef) {
        try {
            return UUID.fromString(sourceRef);
        } catch (IllegalArgumentException ex) {
            throw new BizException(40082, "PIPELINE sourceRef 必须是有效流水线 UUID", ex);
        }
    }

    private Dag requireDag(UUID dagId, UUID tenantId) {
        if (dagId == null) {
            throw new BizException(40400, "Pipeline 不存在");
        }
        return dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required");
        }
        return tenantId;
    }

    private String normalizeEnum(String value) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : "";
    }

    private record NormalizedSubscription(
            String sourceType,
            String sourceRef,
            String condition,
            String freshnessPolicy) {
    }
}

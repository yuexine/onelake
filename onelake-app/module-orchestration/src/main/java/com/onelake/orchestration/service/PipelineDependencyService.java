package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineDependency;
import com.onelake.orchestration.dto.PipelineDependencyDTO;
import com.onelake.orchestration.dto.PipelineDependencyRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineDependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 流水线同周期/跨周期依赖的租户级管理与成环校验服务。 */
@Service
@RequiredArgsConstructor
public class PipelineDependencyService {

    private static final Set<String> DEPENDENCY_TYPES = Set.of("SAME_CYCLE", "CROSS_CYCLE");
    private static final Set<String> OFFSET_GRAINS = Set.of("HOUR", "DAY", "MONTH");

    private final PipelineDependencyRepository dependencyRepo;
    private final DagRepository dagRepo;

    /** 按创建顺序列出当前租户中指定下游的全部依赖。 */
    @Transactional(readOnly = true)
    public List<PipelineDependencyDTO> listDependencies(UUID downstreamDagId) {
        UUID tenantId = requireTenant();
        requireDag(downstreamDagId, tenantId);
        return dependencyRepo
                .findByDownstreamDagIdAndTenantIdOrderByCreatedAtAsc(
                        downstreamDagId, tenantId)
                .stream()
                .map(PipelineDependencyDTO::of)
                .toList();
    }

    /** 一次返回当前租户全部启用依赖边，供编辑器执行本地成环预检。 */
    @Transactional(readOnly = true)
    public List<PipelineDependencyDTO> listEnabledDependencies() {
        return dependencyRepo.findByTenantIdAndEnabledTrue(requireTenant()).stream()
                .map(PipelineDependencyDTO::of)
                .toList();
    }

    /** 新增一条依赖；API 预检与数据库触发器共同保证不会形成有向环。 */
    @Transactional
    public PipelineDependencyDTO createDependency(UUID downstreamDagId,
                                                  PipelineDependencyRequest request) {
        UUID tenantId = requireTenant();
        Dag downstream = requireDag(downstreamDagId, tenantId);
        if (request == null || request.upstreamDagId() == null) {
            throw new BizException(40020, "upstreamDagId 不能为空");
        }
        Dag upstream = requireDag(request.upstreamDagId(), tenantId);
        if (downstream.getId().equals(upstream.getId())) {
            throw new BizException(40021, "流水线不能依赖自身");
        }

        NormalizedDependency normalized = normalize(request);
        List<PipelineDependency> downstreamDependencies = dependencyRepo
                .findByDownstreamDagIdAndTenantIdOrderByCreatedAtAsc(
                        downstreamDagId, tenantId);
        if (downstreamDependencies.stream()
                .anyMatch(existing -> sameDefinition(existing, upstream.getId(), normalized))) {
            throw new BizException(40902, "流水线依赖已存在");
        }

        List<PipelineDependency> enabledDependencies =
                dependencyRepo.findByTenantIdAndEnabledTrue(tenantId);
        if (wouldCreateCycle(downstreamDagId, upstream.getId(), enabledDependencies)) {
            throw new BizException(40903, "新增依赖会形成流水线环路");
        }

        PipelineDependency dependency = new PipelineDependency();
        dependency.setTenantId(tenantId);
        dependency.setDownstreamDagId(downstreamDagId);
        dependency.setUpstreamDagId(upstream.getId());
        dependency.setDependencyType(normalized.dependencyType());
        dependency.setOffsetGrain(normalized.offsetGrain());
        dependency.setOffsetN(normalized.offsetN());
        dependency.setEnabled(true);
        try {
            return PipelineDependencyDTO.of(dependencyRepo.saveAndFlush(dependency));
        } catch (DataIntegrityViolationException ex) {
            String detail = ex.getMostSpecificCause().getMessage();
            if (detail != null && detail.toLowerCase(Locale.ROOT).contains("cycle")) {
                throw new BizException(40903, "新增依赖会形成流水线环路", ex);
            }
            throw new BizException(40902, "流水线依赖冲突或已存在", ex);
        }
    }

    /** 删除当前租户内指定下游的一条依赖。 */
    @Transactional
    public void deleteDependency(UUID downstreamDagId, UUID dependencyId) {
        UUID tenantId = requireTenant();
        requireDag(downstreamDagId, tenantId);
        PipelineDependency dependency = dependencyRepo
                .findByIdAndDownstreamDagIdAndTenantId(
                        dependencyId, downstreamDagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "流水线依赖不存在"));
        dependencyRepo.delete(dependency);
    }

    private static NormalizedDependency normalize(PipelineDependencyRequest request) {
        String dependencyType = normalizeText(request.dependencyType(), "SAME_CYCLE");
        if (!DEPENDENCY_TYPES.contains(dependencyType)) {
            throw new BizException(40022, "dependencyType 仅支持 SAME_CYCLE 或 CROSS_CYCLE");
        }
        int offsetN = request.offsetN() == null ? 0 : request.offsetN();
        if ("SAME_CYCLE".equals(dependencyType)) {
            if (StringUtils.hasText(request.offsetGrain()) || offsetN != 0) {
                throw new BizException(40023, "SAME_CYCLE 不允许配置周期偏移");
            }
            return new NormalizedDependency(dependencyType, null, 0);
        }

        String offsetGrain = normalizeText(request.offsetGrain(), null);
        if (!OFFSET_GRAINS.contains(offsetGrain)) {
            throw new BizException(40023, "CROSS_CYCLE 的 offsetGrain 仅支持 HOUR、DAY 或 MONTH");
        }
        return new NormalizedDependency(dependencyType, offsetGrain, offsetN);
    }

    private static boolean sameDefinition(PipelineDependency existing,
                                          UUID upstreamDagId,
                                          NormalizedDependency requested) {
        return upstreamDagId.equals(existing.getUpstreamDagId())
                && requested.dependencyType().equals(
                        normalizeText(existing.getDependencyType(), "SAME_CYCLE"))
                && java.util.Objects.equals(requested.offsetGrain(),
                        normalizeText(existing.getOffsetGrain(), null))
                && requested.offsetN() == (existing.getOffsetN() == null ? 0 : existing.getOffsetN());
    }

    /**
     * 依赖边方向为 downstream -> upstream；新增边形成环的充要条件是 upstream
     * 已能沿现有启用依赖到达 downstream。
     */
    static boolean wouldCreateCycle(UUID downstreamDagId,
                                    UUID upstreamDagId,
                                    List<PipelineDependency> dependencies) {
        Map<UUID, Set<UUID>> graph = new HashMap<>();
        for (PipelineDependency dependency : dependencies) {
            if (!Boolean.FALSE.equals(dependency.getEnabled())) {
                graph.computeIfAbsent(dependency.getDownstreamDagId(), ignored -> new HashSet<>())
                        .add(dependency.getUpstreamDagId());
            }
        }

        ArrayDeque<UUID> pending = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        pending.add(upstreamDagId);
        while (!pending.isEmpty()) {
            UUID current = pending.removeFirst();
            if (downstreamDagId.equals(current)) {
                return true;
            }
            if (visited.add(current)) {
                pending.addAll(graph.getOrDefault(current, Set.of()));
            }
        }
        return false;
    }

    private Dag requireDag(UUID dagId, UUID tenantId) {
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

    private static String normalizeText(String value, String fallback) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : fallback;
    }

    private record NormalizedDependency(String dependencyType,
                                        String offsetGrain,
                                        int offsetN) {
    }
}

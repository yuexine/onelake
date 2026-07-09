package com.onelake.orchestration.service;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.ComputeProfile;
import com.onelake.orchestration.domain.entity.ResourceGroup;
import com.onelake.orchestration.dto.ComputeProfileDTO;
import com.onelake.orchestration.dto.ComputeProfileRequest;
import com.onelake.orchestration.dto.ResourceGroupDTO;
import com.onelake.orchestration.dto.ResourceGroupRequest;
import com.onelake.orchestration.repository.ComputeProfileRepository;
import com.onelake.orchestration.repository.ResourceGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 编排资源组领域服务。
 *
 * <p>负责租户资源组、计算画像和默认 Spark 资源契约的查询与校验。
 */
@Service
@RequiredArgsConstructor
public class ResourceGroupService {

    private static final Map<String, Set<String>> DEFAULT_RESOURCE_GROUPS_BY_ENGINE = Map.of(
        "SPARK", Set.of("spark-default")
    );
    private static final Map<String, Set<String>> DEFAULT_COMPUTE_PROFILES_BY_GROUP = Map.of(
        "spark-default", Set.of("spark-small", "spark-medium", "spark-large")
    );
    private static final Set<String> SUPPORTED_ENGINES = Set.of("SPARK");

    private final ResourceGroupRepository resourceGroupRepo;
    private final ComputeProfileRepository computeProfileRepo;
    private final AuditLogger audit;

    public static boolean defaultSupportsResourceGroup(String engine, String resourceGroup) {
        if (resourceGroup == null || resourceGroup.isBlank()) {
            return false;
        }
        String normalizedEngine = normalizeEngine(engine);
        return DEFAULT_RESOURCE_GROUPS_BY_ENGINE.getOrDefault(normalizedEngine, Set.of()).contains(resourceGroup);
    }

    public static boolean defaultSupportsComputeProfile(String resourceGroup, String computeProfile) {
        if (resourceGroup == null || resourceGroup.isBlank() || computeProfile == null || computeProfile.isBlank()) {
            return false;
        }
        return DEFAULT_COMPUTE_PROFILES_BY_GROUP.getOrDefault(resourceGroup, Set.of()).contains(computeProfile);
    }

    @Transactional(readOnly = true)
    public List<ResourceGroupDTO> listResourceGroups() {
        UUID tenantId = TenantContext.getTenantId();
        List<ResourceGroup> groups = visibleGroups(tenantId);
        Map<String, ResourceGroup> globalByCode = groups.stream()
            .filter(group -> group.getTenantId() == null)
            .collect(Collectors.toMap(ResourceGroup::getCode, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<UUID, List<ComputeProfile>> profilesByGroup = profilesFor(groups);
        return groups.stream()
            .collect(Collectors.toMap(ResourceGroup::getCode, Function.identity(), (global, tenant) -> tenant,
                LinkedHashMap::new))
            .values()
            .stream()
            .sorted(Comparator.comparing(ResourceGroup::getEngine).thenComparing(ResourceGroup::getCode))
            .map(group -> toDTO(group, profilesForVisibleGroup(group, globalByCode.get(group.getCode()), profilesByGroup)))
            .toList();
    }

    @Transactional
    public ResourceGroupDTO upsertResourceGroup(ResourceGroupRequest request) {
        UUID tenantId = requireTenant();
        String code = normalizeCode(required(request.code(), "resourceGroup.code"));
        String engine = requireSupportedEngine(required(request.engine(), "resourceGroup.engine"),
            "resourceGroup.engine");
        ResourceGroup group = resourceGroupRepo.findByTenantIdAndCode(tenantId, code).orElseGet(ResourceGroup::new);
        boolean created = group.getId() == null;
        group.setTenantId(tenantId);
        group.setCode(code);
        group.setEngine(engine);
        group.setDisplayName(nonBlank(request.displayName(), code));
        group.setStatus(normalizeStatus(request.status()));
        group.setMaxConcurrency(request.maxConcurrency());
        group.setQuotaCpu(request.quotaCpu());
        group.setQuotaMemoryGb(request.quotaMemoryGb());
        group.setCostPolicy(JsonUtil.toJson(request.costPolicy() == null ? Map.of() : request.costPolicy()));
        group.setUpdatedAt(Instant.now());
        group = resourceGroupRepo.save(group);
        if (created) {
            audit.auditCreate("resource_group", group.getId(), auditDetail(group));
        } else {
            audit.auditUpdate("resource_group", group.getId(), auditDetail(group));
        }
        return toDTO(group, computeProfileRepo.findByResourceGroupIdOrderByCodeAsc(group.getId()));
    }

    @Transactional
    public ComputeProfileDTO upsertComputeProfile(String groupCode, ComputeProfileRequest request) {
        UUID tenantId = requireTenant();
        String normalizedGroupCode = normalizeCode(required(groupCode, "resourceGroup.code"));
        ResourceGroup group = resourceGroupRepo.findByTenantIdAndCode(tenantId, normalizedGroupCode)
            .orElseThrow(() -> new BizException(40420, "请先注册租户资源组: " + normalizedGroupCode));
        String code = normalizeCode(required(request.code(), "computeProfile.code"));
        ComputeProfile profile = computeProfileRepo.findByResourceGroupIdAndCode(group.getId(), code)
            .orElseGet(ComputeProfile::new);
        boolean created = profile.getId() == null;
        profile.setResourceGroupId(group.getId());
        profile.setCode(code);
        profile.setEngine(requireSupportedEngine(nonBlank(request.engine(), group.getEngine()),
            "computeProfile.engine"));
        profile.setDisplayName(nonBlank(request.displayName(), code));
        profile.setStatus(normalizeStatus(request.status()));
        profile.setCpuCores(request.cpuCores());
        profile.setMemoryGb(request.memoryGb());
        profile.setMaxScanBytes(request.maxScanBytes());
        profile.setTimeoutSeconds(request.timeoutSeconds());
        profile.setUpdatedAt(Instant.now());
        profile = computeProfileRepo.save(profile);
        if (created) {
            audit.auditCreate("compute_profile", profile.getId(), auditDetail(profile));
        } else {
            audit.auditUpdate("compute_profile", profile.getId(), auditDetail(profile));
        }
        return toDTO(profile);
    }

    @Transactional(readOnly = true)
    public boolean supportsResourceGroup(String engine, String resourceGroup) {
        String normalizedEngine = normalizeEngine(engine);
        Optional<ResourceGroup> group = visibleResourceGroupByCode(TenantContext.getTenantId(), resourceGroup);
        if (group.isEmpty()) {
            return defaultSupportsResourceGroup(normalizedEngine, resourceGroup);
        }
        ResourceGroup item = group.get();
        return "ACTIVE".equalsIgnoreCase(item.getStatus()) && normalizedEngine.equalsIgnoreCase(item.getEngine());
    }

    @Transactional(readOnly = true)
    public boolean supportsComputeProfile(String resourceGroup, String computeProfile) {
        List<ResourceGroup> groups = visibleGroupsForComputeProfile(TenantContext.getTenantId(), resourceGroup);
        if (groups.isEmpty()) {
            return defaultSupportsComputeProfile(resourceGroup, computeProfile);
        }
        for (ResourceGroup group : groups) {
            if (!"ACTIVE".equalsIgnoreCase(group.getStatus())) {
                continue;
            }
            Optional<ComputeProfile> profile = computeProfileRepo.findByResourceGroupIdAndCode(group.getId(), computeProfile);
            if (profile.filter(item -> "ACTIVE".equalsIgnoreCase(item.getStatus())).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private List<ResourceGroup> visibleGroups(UUID tenantId) {
        List<ResourceGroup> groups = new ArrayList<>(resourceGroupRepo.findByTenantIdIsNullOrderByCodeAsc());
        if (tenantId != null) {
            groups.addAll(resourceGroupRepo.findByTenantIdOrderByCodeAsc(tenantId));
        }
        return groups;
    }

    private Optional<ResourceGroup> visibleResourceGroupByCode(UUID tenantId, String code) {
        String normalizedCode = normalizeCode(code);
        if (tenantId != null) {
            Optional<ResourceGroup> tenantGroup = resourceGroupRepo.findByTenantIdAndCode(tenantId, normalizedCode);
            if (tenantGroup.isPresent()) {
                return tenantGroup;
            }
        }
        return resourceGroupRepo.findByTenantIdIsNullAndCode(normalizedCode);
    }

    private List<ResourceGroup> visibleGroupsForComputeProfile(UUID tenantId, String code) {
        String normalizedCode = normalizeCode(code);
        List<ResourceGroup> groups = new ArrayList<>();
        if (tenantId != null) {
            Optional<ResourceGroup> tenantGroup = resourceGroupRepo.findByTenantIdAndCode(tenantId, normalizedCode);
            if (tenantGroup.isPresent()) {
                groups.add(tenantGroup.get());
                resourceGroupRepo.findByTenantIdIsNullAndCode(normalizedCode).ifPresent(groups::add);
                return groups;
            }
        }
        resourceGroupRepo.findByTenantIdIsNullAndCode(normalizedCode).ifPresent(groups::add);
        return groups;
    }

    private Map<UUID, List<ComputeProfile>> profilesFor(List<ResourceGroup> groups) {
        if (groups.isEmpty()) {
            return Map.of();
        }
        return computeProfileRepo.findByResourceGroupIdInOrderByCodeAsc(
                groups.stream().map(ResourceGroup::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(ComputeProfile::getResourceGroupId, LinkedHashMap::new, Collectors.toList()));
    }

    private List<ComputeProfile> profilesForVisibleGroup(
        ResourceGroup group,
        ResourceGroup globalGroup,
        Map<UUID, List<ComputeProfile>> profilesByGroup
    ) {
        Map<String, ComputeProfile> profiles = new LinkedHashMap<>();
        if (globalGroup != null) {
            profilesByGroup.getOrDefault(globalGroup.getId(), List.of())
                .forEach(profile -> profiles.put(profile.getCode(), profile));
        }
        profilesByGroup.getOrDefault(group.getId(), List.of())
            .forEach(profile -> profiles.put(profile.getCode(), profile));
        return profiles.values().stream()
            .sorted(Comparator.comparing(ComputeProfile::getCode))
            .toList();
    }

    private ResourceGroupDTO toDTO(ResourceGroup group, List<ComputeProfile> profiles) {
        return new ResourceGroupDTO(
            group.getId(),
            group.getCode(),
            group.getDisplayName(),
            group.getEngine(),
            group.getStatus(),
            group.getTenantId() == null,
            group.getMaxConcurrency(),
            group.getQuotaCpu(),
            group.getQuotaMemoryGb(),
            parseCostPolicy(group.getCostPolicy()),
            profiles.stream().map(this::toDTO).toList(),
            group.getCreatedAt(),
            group.getUpdatedAt()
        );
    }

    private ComputeProfileDTO toDTO(ComputeProfile profile) {
        return new ComputeProfileDTO(
            profile.getId(),
            profile.getCode(),
            profile.getDisplayName(),
            profile.getEngine(),
            profile.getStatus(),
            profile.getCpuCores(),
            profile.getMemoryGb(),
            profile.getMaxScanBytes(),
            profile.getTimeoutSeconds(),
            profile.getCreatedAt(),
            profile.getUpdatedAt()
        );
    }

    private Map<String, Object> parseCostPolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Object parsed = JsonUtil.fromJson(raw, Map.class);
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        return tenantId;
    }

    private static String normalizeEngine(String engine) {
        return nonBlank(engine, "SPARK").toUpperCase(Locale.ROOT);
    }

    private static String requireSupportedEngine(String engine, String field) {
        String normalized = normalizeEngine(engine);
        if (!SUPPORTED_ENGINES.contains(normalized)) {
            throw new BizException(40037, field + " 仅支持 SPARK");
        }
        return normalized;
    }

    private static String normalizeCode(String code) {
        return nonBlank(code, "").trim();
    }

    private static String normalizeStatus(String status) {
        String normalized = nonBlank(status, "ACTIVE").toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DISABLED", "DEPRECATED").contains(normalized)) {
            throw new BizException(40036, "资源状态不支持: " + status);
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(40035, field + " 不能为空");
        }
        return value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Map<String, Object> auditDetail(ResourceGroup group) {
        return Map.of(
            "code", group.getCode(),
            "engine", group.getEngine(),
            "status", group.getStatus()
        );
    }

    private Map<String, Object> auditDetail(ComputeProfile profile) {
        return Map.of(
            "code", profile.getCode(),
            "engine", profile.getEngine(),
            "status", profile.getStatus(),
            "resourceGroupId", profile.getResourceGroupId()
        );
    }
}

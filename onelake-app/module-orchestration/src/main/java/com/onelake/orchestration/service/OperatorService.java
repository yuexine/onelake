package com.onelake.orchestration.service;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.config.BuiltInOperatorCatalog;
import com.onelake.orchestration.domain.entity.Operator;
import com.onelake.orchestration.domain.entity.OperatorInstall;
import com.onelake.orchestration.domain.entity.OperatorVersion;
import com.onelake.orchestration.domain.enums.CompileTarget;
import com.onelake.orchestration.domain.enums.OperatorCategory;
import com.onelake.orchestration.domain.enums.OperatorScope;
import com.onelake.orchestration.domain.enums.OperatorStatus;
import com.onelake.orchestration.dto.OperatorDTO;
import com.onelake.orchestration.dto.OperatorInstallRequest;
import com.onelake.orchestration.dto.OperatorManifestDTO;
import com.onelake.orchestration.dto.OperatorValidationResultDTO;
import com.onelake.orchestration.dto.OperatorVersionDTO;
import com.onelake.orchestration.dto.OperatorVersionRequest;
import com.onelake.orchestration.dto.UpdateOperatorRequest;
import com.onelake.orchestration.repository.OperatorInstallRepository;
import com.onelake.orchestration.repository.OperatorRepository;
import com.onelake.orchestration.repository.OperatorVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * 算子市场领域服务。
 *
 * <p>负责算子可见性、Manifest 校验、版本管理、租户安装和内置算子种子数据写入。
 */
@Service
@RequiredArgsConstructor
public class OperatorService {

    private static final Pattern OPERATOR_REF_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$");
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> FIELD_REF_STOP_WORDS = Set.of(
        "and", "or", "not", "null", "is", "case", "when", "then", "else", "end", "as",
        "asc", "desc", "first", "last", "days", "day", "date", "month", "year", "hour",
        "coalesce", "cast", "trim", "upper", "lower", "regexp_replace", "sha256", "md5"
    );
    private final OperatorRepository operatorRepo;
    private final OperatorVersionRepository versionRepo;
    private final OperatorInstallRepository installRepo;
    private final AuditLogger audit;
    private final ResourceGroupService resourceGroupService;

    @Transactional(readOnly = true)
    public List<OperatorDTO> listOperators(String category, String scope, String keyword) {
        UUID tenantId = requireTenant();
        List<OperatorInstall> installs = installRepo.findByTenantId(tenantId);
        Map<UUID, OperatorInstall> installMap = installs.stream()
            .collect(Collectors.toMap(OperatorInstall::getOperatorId, i -> i, (a, b) -> a));

        List<Operator> operators = new ArrayList<>();
        operators.addAll(operatorRepo.findByScopeAndTenantIdIsNull(OperatorScope.BUILTIN));
        operators.addAll(operatorRepo.findByTenantIdAndScopeIn(tenantId,
            List.of(OperatorScope.CUSTOM, OperatorScope.TENANT_PRIVATE)));
        addInstalledOperators(operators, installs);

        OperatorCategory categoryFilter = parseEnum(OperatorCategory.class, category, false);
        OperatorScope scopeFilter = parseEnum(OperatorScope.class, scope, false);
        String normalizedKeyword = normalizeKeyword(keyword);
        Set<UUID> seen = new HashSet<>();
        return operators.stream()
            .filter(op -> seen.add(op.getId()))
            .filter(op -> categoryFilter == null || op.getCategory() == categoryFilter)
            .filter(op -> scopeFilter == null || op.getScope() == scopeFilter)
            .filter(op -> matchesKeyword(op, normalizedKeyword))
            .sorted(Comparator.comparing((Operator op) -> op.getCategory().ordinal())
                .thenComparing(Operator::getOperatorRef))
            .map(op -> toDTO(op, installMap.get(op.getId()), false))
            .toList();
    }

    @Transactional(readOnly = true)
    public OperatorDTO getOperator(String ref) {
        UUID tenantId = requireTenant();
        Operator operator = findVisibleOperator(tenantId, ref)
            .orElseThrow(() -> new BizException(40400, "算子不存在: " + ref));
        OperatorInstall install = installRepo.findByTenantIdAndOperatorId(tenantId, operator.getId()).orElse(null);
        return toDTO(operator, install, true);
    }

    @Transactional(readOnly = true)
    public OperatorValidationResultDTO validateOperator(OperatorManifestDTO manifest) {
        return validate(manifest);
    }

    @Transactional(readOnly = true)
    public OperatorValidationResultDTO validateGraph(Map<String, Object> request) {
        UUID tenantId = requireTenant();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> graph = extractGraph(request, errors);
        if (graph == null) {
            return new OperatorValidationResultDTO(false, errors, warnings);
        }

        List<Map<String, Object>> nodes = objectList(graph.get("nodes"), "nodes", errors);
        List<Map<String, Object>> edges = objectList(graph.get("edges"), "edges", errors);
        if (nodes == null || edges == null) {
            return new OperatorValidationResultDTO(false, errors, warnings);
        }

        Map<String, Map<String, Object>> nodeById = indexNodes(nodes, errors);
        Map<String, Integer> inbound = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> inboundEdges = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (String nodeId : nodeById.keySet()) {
            inbound.put(nodeId, 0);
            inboundEdges.put(nodeId, new ArrayList<>());
            outgoing.put(nodeId, new ArrayList<>());
        }
        validateEdges(edges, nodeById.keySet(), inbound, inboundEdges, outgoing, errors);
        if (hasCycle(outgoing)) {
            errors.add("DAG 存在环路");
        }

        for (Map<String, Object> node : nodes) {
            validateGraphNode(tenantId, node, inbound, inboundEdges, errors, warnings);
        }
        validateFieldSchemaAndGovernance(graph, nodes, errors, warnings);
        validateExecutionResourceContract(graph, nodes, errors, warnings);
        return new OperatorValidationResultDTO(errors.isEmpty(), errors, warnings);
    }

    @Transactional
    public OperatorDTO registerOperator(OperatorManifestDTO manifest) {
        UUID tenantId = requireTenant();
        OperatorValidationResultDTO validation = validate(manifest);
        if (!validation.ok()) {
            throw new BizException(40030, "算子 Manifest 校验失败: " + String.join("; ", validation.errors()));
        }
        OperatorScope scope = parseEnum(OperatorScope.class, manifest.scope(), true);
        if (scope == OperatorScope.BUILTIN) {
            throw new BizException(40031, "内置算子只能通过系统种子数据注册");
        }
        operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(tenantId, manifest.operatorRef(),
            List.of(OperatorScope.CUSTOM, OperatorScope.TENANT_PRIVATE)).ifPresent(op -> {
                throw new BizException(40910, "当前租户下算子标识已存在: " + manifest.operatorRef());
            });

        Operator operator = new Operator();
        operator.setTenantId(tenantId);
        operator.setOperatorRef(manifest.operatorRef());
        operator.setCategory(parseEnum(OperatorCategory.class, manifest.category(), true));
        operator.setScope(scope);
        operator.setDisplayName(manifest.displayName().trim());
        operator.setDescription(manifest.description());
        operator.setLatestVersion(manifest.version());
        operator.setStatus(OperatorStatus.ACTIVE);
        operator = operatorRepo.save(operator);
        saveVersion(operator, manifest, "custom operator registered", false);
        audit.auditCreate("operator", operator.getId(),
            Map.of("operatorRef", operator.getOperatorRef(), "scope", operator.getScope().name()));
        return toDTO(operator, null, true);
    }

    @Transactional
    public OperatorDTO publishVersion(String ref, OperatorVersionRequest request) {
        UUID tenantId = requireTenant();
        Operator operator = findTenantMutableOperator(tenantId, ref);
        OperatorManifestDTO manifest = request.manifest();
        if (manifest == null || !Objects.equals(ref, manifest.operatorRef())) {
            throw new BizException(40032, "版本 Manifest 的 operatorRef 必须与路径一致");
        }
        OperatorValidationResultDTO validation = validate(manifest);
        if (!validation.ok()) {
            throw new BizException(40030, "算子 Manifest 校验失败: " + String.join("; ", validation.errors()));
        }
        operator.setCategory(parseEnum(OperatorCategory.class, manifest.category(), true));
        operator.setDisplayName(manifest.displayName().trim());
        operator.setDescription(manifest.description());
        operator.setLatestVersion(manifest.version());
        operatorRepo.save(operator);
        saveVersion(operator, manifest, request.changelog(), false);
        audit.audit("PUBLISH_VERSION", "operator", operator.getId().toString(),
            Map.of("operatorRef", ref, "version", manifest.version()));
        OperatorInstall install = installRepo.findByTenantIdAndOperatorId(tenantId, operator.getId()).orElse(null);
        return toDTO(operator, install, true);
    }

    @Transactional
    public OperatorDTO updateOperator(String ref, UpdateOperatorRequest request) {
        UUID tenantId = requireTenant();
        Operator operator = findTenantMutableOperator(tenantId, ref);
        if (request.displayName() != null && !request.displayName().isBlank()) {
            operator.setDisplayName(request.displayName().trim());
        }
        if (request.description() != null) {
            operator.setDescription(request.description());
        }
        if (request.status() != null && !request.status().isBlank()) {
            operator.setStatus(parseEnum(OperatorStatus.class, request.status(), true));
        }
        operatorRepo.save(operator);
        audit.auditUpdate("operator", operator.getId(),
            Map.of("operatorRef", ref, "status", operator.getStatus().name()));
        OperatorInstall install = installRepo.findByTenantIdAndOperatorId(tenantId, operator.getId()).orElse(null);
        return toDTO(operator, install, true);
    }

    @Transactional
    public OperatorDTO installOperator(String ref, OperatorInstallRequest request) {
        UUID tenantId = requireTenant();
        Operator operator = findVisibleOperator(tenantId, ref)
            .orElseThrow(() -> new BizException(40400, "算子不存在: " + ref));
        if (operator.getStatus() == OperatorStatus.DEPRECATED) {
            throw new BizException(40033, "已废弃算子不能安装或锁定版本: " + ref);
        }
        String pinnedVersion = request == null ? null : blankToNull(request.pinnedVersion());
        if (pinnedVersion != null && versionRepo.findByOperatorIdAndVersion(operator.getId(), pinnedVersion).isEmpty()) {
            throw new BizException(40401, "算子版本不存在: " + pinnedVersion);
        }
        OperatorInstall install = installRepo.findByTenantIdAndOperatorId(tenantId, operator.getId())
            .orElseGet(OperatorInstall::new);
        install.setTenantId(tenantId);
        install.setOperatorId(operator.getId());
        install.setPinnedVersion(pinnedVersion);
        installRepo.save(install);
        audit.audit("INSTALL", "operator", operator.getId().toString(),
            Map.of("operatorRef", ref, "pinnedVersion", pinnedVersion == null ? "" : pinnedVersion));
        return toDTO(operator, install, true);
    }

    @Transactional
    public int seedBuiltIns() {
        int count = 0;
        for (OperatorManifestDTO manifest : BuiltInOperatorCatalog.manifests()) {
            OperatorValidationResultDTO validation = validate(manifest);
            if (!validation.ok()) {
                throw new IllegalStateException("invalid built-in operator " + manifest.operatorRef() + ": "
                    + validation.errors());
            }
            Operator operator = operatorRepo
                .findByOperatorRefAndScopeAndTenantIdIsNull(manifest.operatorRef(), OperatorScope.BUILTIN)
                .orElseGet(Operator::new);
            operator.setTenantId(null);
            operator.setOperatorRef(manifest.operatorRef());
            operator.setCategory(parseEnum(OperatorCategory.class, manifest.category(), true));
            operator.setScope(OperatorScope.BUILTIN);
            operator.setDisplayName(manifest.displayName());
            operator.setDescription(manifest.description());
            operator.setLatestVersion(manifest.version());
            operator.setStatus(OperatorStatus.ACTIVE);
            operator = operatorRepo.save(operator);
            saveVersion(operator, manifest, "builtin operator seed", true);
            count++;
        }
        return count;
    }

    private void addInstalledOperators(List<Operator> operators, List<OperatorInstall> installs) {
        for (OperatorInstall install : installs) {
            operatorRepo.findById(install.getOperatorId()).ifPresent(operators::add);
        }
    }

    private Optional<Operator> findVisibleOperator(UUID tenantId, String ref) {
        return operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(tenantId, ref,
                List.of(OperatorScope.CUSTOM, OperatorScope.TENANT_PRIVATE))
            .or(() -> operatorRepo.findByOperatorRefAndScopeAndTenantIdIsNull(ref, OperatorScope.BUILTIN))
            .or(() -> findInstalledOperator(tenantId, ref));
    }

    private Optional<Operator> findInstalledOperator(UUID tenantId, String ref) {
        Set<UUID> installedIds = installRepo.findByTenantId(tenantId).stream()
            .map(OperatorInstall::getOperatorId)
            .collect(Collectors.toSet());
        return operatorRepo.findByOperatorRef(ref).stream()
            .filter(op -> installedIds.contains(op.getId()))
            .findFirst();
    }

    private Operator findTenantMutableOperator(UUID tenantId, String ref) {
        return operatorRepo.findByTenantIdAndOperatorRefAndScopeIn(tenantId, ref,
                List.of(OperatorScope.CUSTOM, OperatorScope.TENANT_PRIVATE))
            .orElseThrow(() -> new BizException(40400, "当前租户下可维护算子不存在: " + ref));
    }

    private void saveVersion(Operator operator, OperatorManifestDTO manifest, String changelog, boolean overwrite) {
        Optional<OperatorVersion> existing = versionRepo.findByOperatorIdAndVersion(operator.getId(), manifest.version());
        if (existing.isPresent() && !overwrite) {
            throw new BizException(40911, "算子版本已存在: " + manifest.version());
        }
        OperatorVersion version = existing.orElseGet(OperatorVersion::new);
        version.setOperatorId(operator.getId());
        version.setVersion(manifest.version());
        version.setManifest(JsonUtil.toJson(manifest));
        version.setChangelog(changelog);
        version.setCreatedBy(TenantContext.getUserId());
        versionRepo.save(version);
    }

    private OperatorDTO toDTO(Operator operator, OperatorInstall install, boolean includeVersions) {
        OperatorManifestDTO manifest = versionRepo.findByOperatorIdAndVersion(operator.getId(), operator.getLatestVersion())
            .map(this::manifestFromVersion)
            .orElse(null);
        List<OperatorVersionDTO> versions = includeVersions
            ? versionRepo.findByOperatorIdOrderByCreatedAtDesc(operator.getId()).stream()
                .map(this::toVersionDTO)
                .toList()
            : List.of();
        boolean installed = operator.getScope() == OperatorScope.BUILTIN || install != null;
        return new OperatorDTO(
            operator.getId(),
            operator.getOperatorRef(),
            operator.getCategory().name(),
            operator.getScope().name(),
            operator.getDisplayName(),
            operator.getDescription(),
            operator.getLatestVersion(),
            operator.getStatus().name(),
            installed,
            install == null ? null : install.getPinnedVersion(),
            manifest,
            versions,
            operator.getCreatedAt()
        );
    }

    private OperatorVersionDTO toVersionDTO(OperatorVersion version) {
        return new OperatorVersionDTO(
            version.getId(),
            version.getVersion(),
            manifestFromVersion(version),
            version.getChangelog(),
            version.getCreatedBy(),
            version.getCreatedAt()
        );
    }

    private OperatorManifestDTO manifestFromVersion(OperatorVersion version) {
        return JsonUtil.fromJson(version.getManifest(), OperatorManifestDTO.class);
    }

    private OperatorValidationResultDTO validate(OperatorManifestDTO manifest) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (manifest == null) {
            return new OperatorValidationResultDTO(false, List.of("Manifest 不能为空"), warnings);
        }
        if (manifest.operatorRef() == null || !OPERATOR_REF_PATTERN.matcher(manifest.operatorRef()).matches()) {
            errors.add("operatorRef 必须符合 <category>.<name> 小写命名");
        }
        if (manifest.version() == null || !SEMVER_PATTERN.matcher(manifest.version()).matches()) {
            errors.add("version 必须是 semver，例如 1.0.0");
        }
        OperatorCategory category = parseEnum(OperatorCategory.class, manifest.category(), false);
        if (category == null) {
            errors.add("category 不在支持范围内");
        }
        if (parseEnum(OperatorScope.class, manifest.scope(), false) == null) {
            errors.add("scope 不在支持范围内");
        }
        CompileTarget compileTarget = parseEnum(CompileTarget.class, manifest.compileTarget(), false);
        if (compileTarget == null) {
            errors.add("compileTarget 不在支持范围内");
        } else {
            validateTemplateContract(compileTarget, manifest.template(), errors);
            validateResourceHint(compileTarget, manifest.resourceHint(), errors, warnings);
        }
        if (manifest.displayName() == null || manifest.displayName().isBlank()) {
            errors.add("displayName 不能为空");
        }
        if (manifest.paramsSchema() == null || !"object".equals(manifest.paramsSchema().get("type"))) {
            errors.add("paramsSchema.type 必须为 object");
        }
        if (manifest.outputSchema() == null || manifest.outputSchema().get("mode") == null) {
            errors.add("outputSchema.mode 不能为空");
        }
        if (category == OperatorCategory.QUALITY_GATE) {
            Object action = manifest.policy() == null ? null : manifest.policy().get("actionOnViolation");
            if (action == null || String.valueOf(action).isBlank()) {
                errors.add("QUALITY_GATE 算子必须声明 policy.actionOnViolation");
            }
        }
        if (manifest.inputPorts() == null) {
            errors.add("inputPorts 不能为空；无输入算子请使用空数组");
        }
        return new OperatorValidationResultDTO(errors.isEmpty(), errors, warnings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractGraph(Map<String, Object> request, List<String> errors) {
        if (request == null || request.isEmpty()) {
            errors.add("operator graph 不能为空");
            return null;
        }
        if (request.get("graph") instanceof Map<?, ?> graph) {
            return (Map<String, Object>) graph;
        }
        if (request.get("operatorGraph") instanceof Map<?, ?> graph) {
            return (Map<String, Object>) graph;
        }
        if (request.get("definition") instanceof Map<?, ?> definition) {
            Map<String, Object> def = (Map<String, Object>) definition;
            if (def.get("operatorGraph") instanceof Map<?, ?> graph) {
                return (Map<String, Object>) graph;
            }
            return def;
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value, String field, List<String> errors) {
        if (!(value instanceof List<?> raw)) {
            errors.add(field + " 必须是数组");
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Object item = raw.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.add(field + "[" + i + "] 必须是对象");
                continue;
            }
            items.add((Map<String, Object>) map);
        }
        return items;
    }

    private Map<String, Map<String, Object>> indexNodes(List<Map<String, Object>> nodes, List<String> errors) {
        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = nodes.get(i);
            String id = textValue(node.get("id"));
            if (id == null) {
                errors.add("nodes[" + i + "].id 不能为空");
                continue;
            }
            if (nodeById.putIfAbsent(id, node) != null) {
                errors.add("节点 id 重复: " + id);
            }
        }
        return nodeById;
    }

    private void validateEdges(
        List<Map<String, Object>> edges,
        Set<String> nodeIds,
        Map<String, Integer> inbound,
        Map<String, List<Map<String, Object>>> inboundEdges,
        Map<String, List<String>> outgoing,
        List<String> errors
    ) {
        for (int i = 0; i < edges.size(); i++) {
            Map<String, Object> edge = edges.get(i);
            String source = textValue(edge.get("source"));
            String target = textValue(edge.get("target"));
            if (source == null || target == null) {
                errors.add("edges[" + i + "] 必须包含 source/target");
                continue;
            }
            if (!nodeIds.contains(source)) {
                errors.add("边引用了不存在的 source 节点: " + source);
            }
            if (!nodeIds.contains(target)) {
                errors.add("边引用了不存在的 target 节点: " + target);
            }
            if (nodeIds.contains(source) && nodeIds.contains(target)) {
                inbound.put(target, inbound.getOrDefault(target, 0) + 1);
                inboundEdges.computeIfAbsent(target, key -> new ArrayList<>()).add(edge);
                outgoing.computeIfAbsent(source, key -> new ArrayList<>()).add(target);
            }
        }
    }

    private void validateGraphNode(
        UUID tenantId,
        Map<String, Object> node,
        Map<String, Integer> inbound,
        Map<String, List<Map<String, Object>>> inboundEdges,
        List<String> errors,
        List<String> warnings
    ) {
        String id = textValue(node.get("id"));
        if (id == null) {
            return;
        }
        String nodeType = firstText(textValue(node.get("nodeType")), textValue(node.get("type")));
        if (nodeType == null) {
            errors.add("节点 " + id + " 缺少 nodeType/type");
        }
        String operatorRef = textValue(node.get("operatorRef"));
        if (operatorRef == null) {
            errors.add("节点 " + id + " 缺少 operatorRef");
            return;
        }

        Operator operator = findVisibleOperator(tenantId, operatorRef)
            .orElse(null);
        if (operator == null) {
            errors.add("节点 " + id + " 引用了不可见算子: " + operatorRef);
            return;
        }
        if (operator.getStatus() == OperatorStatus.DEPRECATED) {
            errors.add("节点 " + id + " 引用了已废弃算子: " + operatorRef);
            return;
        }
        String version = firstText(textValue(node.get("operatorVersion")), operator.getLatestVersion());
        if (textValue(node.get("operatorVersion")) == null) {
            warnings.add("节点 " + id + " 未指定 operatorVersion，按 latestVersion=" + version + " 校验");
        }
        OperatorVersion operatorVersion = versionRepo.findByOperatorIdAndVersion(operator.getId(), version)
            .orElse(null);
        if (operatorVersion == null) {
            errors.add("节点 " + id + " 引用了不存在的算子版本: " + operatorRef + "@" + version);
            return;
        }
        OperatorManifestDTO manifest = manifestFromVersion(operatorVersion);
        OperatorValidationResultDTO manifestValidation = validate(manifest);
        if (!manifestValidation.ok()) {
            errors.add("节点 " + id + " Manifest 自校验失败: " + String.join("; ", manifestValidation.errors()));
        }
        warnings.addAll(manifestValidation.warnings().stream()
            .map(warning -> "节点 " + id + ": " + warning)
            .toList());
        validateManifestMatch(id, nodeType, operatorRef, manifest, errors);
        validateConfig(id, node.get("config"), manifest, errors);
        validateInputPorts(id, inbound.getOrDefault(id, 0), inboundEdges.getOrDefault(id, List.of()), manifest, errors);
    }

    private void validateManifestMatch(
        String nodeId,
        String nodeType,
        String operatorRef,
        OperatorManifestDTO manifest,
        List<String> errors
    ) {
        if (manifest == null) {
            errors.add("节点 " + nodeId + " Manifest 为空");
            return;
        }
        if (!Objects.equals(operatorRef, manifest.operatorRef())) {
            errors.add("节点 " + nodeId + " operatorRef 与 Manifest 不一致");
        }
        if (nodeType != null && !nodeType.equalsIgnoreCase(manifest.category())) {
            errors.add("节点 " + nodeId + " nodeType 与 Manifest category 不一致");
        }
        if (!"SPARK".equalsIgnoreCase(manifest.compileTarget())) {
            errors.add("节点 " + nodeId + " compileTarget=" + manifest.compileTarget()
                + " 不在 Spark-only 图级执行闭环内");
        }
    }

    private void validateTemplateContract(
        CompileTarget compileTarget,
        Map<String, Object> template,
        List<String> errors
    ) {
        if (template == null) {
            errors.add("template 不能为空");
            return;
        }
        String kind = textValue(template.get("kind"));
        if (kind == null) {
            errors.add("template.kind 不能为空");
            return;
        }
        switch (compileTarget) {
            case SPARK -> {
                String normalizedKind = kind.toUpperCase(Locale.ROOT);
                if (!Set.of("SPARK_SQL", "PYSPARK", "SELECT_EXPR", "RAW_SQL", "COLUMN_EXPR",
                    "FILTER", "JOIN", "AGG", "SPARK_SINK", "QUALITY_ASSERT").contains(normalizedKind)) {
                    errors.add("SPARK template.kind 不在支持范围内");
                }
                if ("SPARK_SQL".equals(normalizedKind) && textValue(template.get("sql")) == null) {
                    errors.add("SPARK_SQL template.sql 不能为空");
                }
                if ("PYSPARK".equals(normalizedKind) && textValue(template.get("entrypoint")) == null) {
                    errors.add("PYSPARK template.entrypoint 不能为空");
                }
                if (!"PYSPARK".equals(normalizedKind) && textValue(template.get("sql")) == null) {
                    errors.add("SPARK template.sql 不能为空");
                }
            }
        }
    }

    private void validateResourceHint(
        CompileTarget compileTarget,
        Map<String, Object> resourceHint,
        List<String> errors,
        List<String> warnings
    ) {
        if (resourceHint == null) {
            errors.add("compileTarget=" + compileTarget.name()
                + " 必须声明 resourceHint.defaultResourceGroup 与 resourceHint.engine");
            return;
        }
        String engine = textValue(resourceHint.get("engine"));
        String resourceGroup = textValue(resourceHint.get("defaultResourceGroup"));
        if (resourceGroup == null) {
            errors.add("compileTarget=" + compileTarget.name() + " 必须声明 resourceHint.defaultResourceGroup");
        }
        if (engine == null) {
            errors.add("compileTarget=" + compileTarget.name() + " 必须声明 resourceHint.engine");
            return;
        }
        if (!compileTarget.name().equalsIgnoreCase(engine)) {
            errors.add("compileTarget=" + compileTarget.name() + " 的 resourceHint.engine 必须为 "
                + compileTarget.name());
        }
        if (resourceGroup != null) {
            validateResourceGroup(compileTarget.name(), resourceGroup, errors);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateConfig(String nodeId, Object configValue, OperatorManifestDTO manifest, List<String> errors) {
        Map<String, Object> config = configValue instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
        for (String required : requiredParams(manifest)) {
            if (!configContainsRequired(config, required)) {
                errors.add("节点 " + nodeId + " 缺少必需参数: " + required);
            }
        }
    }

    private boolean configContainsRequired(Map<String, Object> config, String required) {
        if (config.containsKey(required)) {
            return true;
        }
        return "column".equals(required) && config.get("columns") instanceof List<?> values && !values.isEmpty();
    }

    private List<String> requiredParams(OperatorManifestDTO manifest) {
        if (manifest == null || manifest.paramsSchema() == null) {
            return List.of();
        }
        Object required = manifest.paramsSchema().get("required");
        if (!(required instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(String::valueOf)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private void validateInputPorts(
        String nodeId,
        int inboundCount,
        List<Map<String, Object>> inboundEdges,
        OperatorManifestDTO manifest,
        List<String> errors
    ) {
        if (manifest == null || manifest.inputPorts() == null) {
            return;
        }
        if (manifest.inputPorts().isEmpty()) {
            if (inboundCount > 0) {
                errors.add("节点 " + nodeId + " 不应有输入边");
            }
            return;
        }
        int minInputs = 0;
        int maxInputs = 0;
        boolean hasMany = false;
        Map<String, String> portCardinality = new LinkedHashMap<>();
        for (Map<String, Object> port : manifest.inputPorts()) {
            String name = textValue(port.get("name"));
            String cardinality = String.valueOf(port.getOrDefault("cardinality", "ONE"));
            if (name != null) {
                portCardinality.put(name, cardinality);
            }
            if ("MANY".equalsIgnoreCase(cardinality)) {
                hasMany = true;
                minInputs++;
            } else {
                minInputs++;
                maxInputs++;
            }
        }
        if (inboundCount < minInputs) {
            errors.add("节点 " + nodeId + " 输入边不足，至少需要 " + minInputs + " 条");
        }
        if (!hasMany && inboundCount > maxInputs) {
            errors.add("节点 " + nodeId + " 输入边过多，最多允许 " + maxInputs + " 条");
        }
        validateTargetPorts(nodeId, inboundEdges, portCardinality, errors);
    }

    @SuppressWarnings("unchecked")
    private void validateFieldSchemaAndGovernance(
        Map<String, Object> graph,
        List<Map<String, Object>> nodes,
        List<String> errors,
        List<String> warnings
    ) {
        FieldGraphContext context = fieldGraphContext(graph, nodes);
        if (context.sourceColumns().isEmpty() && context.outputColumns().isEmpty()) {
            warnings.add("operator graph 未提供 sourceColumns/outputColumns，跳过字段 schema 闭合强校验");
            return;
        }
        if (context.sourceColumns().isEmpty()) {
            warnings.add("operator graph 未提供 sourceColumns，仅做节点配置与输出字段自一致校验");
        } else if (!context.hasGovernanceMeta()) {
            warnings.add("sourceColumns 未携带 classification/piiType/suggestLevel，跳过敏感字段强校验");
        }

        Set<String> currentSchema = new LinkedHashSet<>(context.sourceColumns());
        Set<String> sensitiveColumns = new LinkedHashSet<>(context.sensitiveColumns());
        Set<String> governedColumns = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String nodeId = textValue(node.get("id"));
            String operatorRef = textValue(node.get("operatorRef"));
            String nodeType = firstText(textValue(node.get("nodeType")), textValue(node.get("type")));
            Map<String, Object> config = node.get("config") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
            if (nodeId == null) {
                continue;
            }

            if (!currentSchema.isEmpty()) {
                for (String field : referencedColumns(config)) {
                    if (!currentSchema.contains(field)) {
                        errors.add("节点 " + nodeId + " 引用了不存在的字段: " + field);
                    }
                }
            }

            if (isProtectingOperator(nodeType, operatorRef)) {
                governedColumns.addAll(governedColumns(config, currentSchema));
            }

            SchemaStep schemaStep = deriveNodeSchema(nodeId, operatorRef, nodeType, config, currentSchema, sensitiveColumns, warnings);
            currentSchema = schemaStep.columns();
            sensitiveColumns = schemaStep.sensitiveColumns();
        }

        if (!context.outputColumns().isEmpty() && !currentSchema.isEmpty()) {
            Set<String> missing = new LinkedHashSet<>(context.outputColumns());
            missing.removeAll(currentSchema);
            for (String column : missing) {
                errors.add("输出字段未由上游 schema 产生: " + column);
            }
        }
        Set<String> exposedSensitive = new LinkedHashSet<>(sensitiveColumns);
        if (!context.outputColumns().isEmpty()) {
            exposedSensitive.retainAll(context.outputColumns());
        }
        exposedSensitive.removeAll(governedColumns);
        for (String column : exposedSensitive) {
            errors.add("敏感字段 " + column + " 透传到输出但未经过 MASK/ENCRYPT 算子");
        }
    }

    private FieldGraphContext fieldGraphContext(Map<String, Object> graph, List<Map<String, Object>> nodes) {
        Set<String> sourceColumns = firstNonEmptyColumns(graph.get("sourceColumns"), graph.get("inputColumns"));
        if (sourceColumns.isEmpty()) {
            sourceColumns = columnsFromInputNodes(nodes);
        }
        Set<String> outputColumns = firstNonEmptyColumns(graph.get("outputColumns"), graph.get("targetColumns"));
        if (outputColumns.isEmpty()) {
            outputColumns = columnsFromOutputNodes(nodes);
        }
        Map<String, ColumnGovernance> governance = columnGovernance(graph.get("sourceColumns"));
        if (governance.isEmpty()) {
            governance = columnGovernance(graph.get("inputColumns"));
        }
        Set<String> sensitiveColumns = sensitiveColumns(governance);
        sensitiveColumns.addAll(columnNames(graph.get("sensitiveColumns")));
        return new FieldGraphContext(sourceColumns, outputColumns, sensitiveColumns, !governance.isEmpty());
    }

    private Set<String> firstNonEmptyColumns(Object first, Object second) {
        Set<String> columns = columnNames(first);
        if (!columns.isEmpty()) {
            return columns;
        }
        return columnNames(second);
    }

    @SuppressWarnings("unchecked")
    private Set<String> columnsFromInputNodes(List<Map<String, Object>> nodes) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String nodeType = firstText(textValue(node.get("nodeType")), textValue(node.get("type")));
            if (!"INPUT".equalsIgnoreCase(String.valueOf(nodeType))) {
                continue;
            }
            Map<String, Object> config = node.get("config") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
            columns.addAll(columnNames(config.get("columns")));
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private Set<String> columnsFromOutputNodes(List<Map<String, Object>> nodes) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String nodeType = firstText(textValue(node.get("nodeType")), textValue(node.get("type")));
            if (!"OUTPUT".equalsIgnoreCase(String.valueOf(nodeType))) {
                continue;
            }
            Map<String, Object> config = node.get("config") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
            columns.addAll(columnNames(config.get("columns")));
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ColumnGovernance> columnGovernance(Object value) {
        Map<String, ColumnGovernance> governance = new LinkedHashMap<>();
        if (!(value instanceof List<?> items)) {
            return governance;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> column = (Map<String, Object>) raw;
            String name = normalizeColumn(textValue(firstPresent(column, "name", "column", "target")));
            if (name == null) {
                continue;
            }
            governance.put(name, new ColumnGovernance(
                textValue(column.get("classification")),
                textValue(column.get("piiType")),
                textValue(column.get("suggestLevel"))
            ));
        }
        return governance;
    }

    private Set<String> sensitiveColumns(Map<String, ColumnGovernance> governance) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map.Entry<String, ColumnGovernance> entry : governance.entrySet()) {
            ColumnGovernance item = entry.getValue();
            if (isSensitiveLevel(item.classification())
                || isSensitiveLevel(item.suggestLevel())
                || item.piiType() != null) {
                columns.add(entry.getKey());
            }
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private Set<String> columnNames(Object value) {
        Set<String> columns = new LinkedHashSet<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> column = (Map<String, Object>) raw;
                    String name = normalizeColumn(textValue(firstPresent(column, "name", "column", "target")));
                    if (name != null) {
                        columns.add(name);
                    }
                } else {
                    addColumnReference(columns, item);
                }
            }
            return columns;
        }
        addColumnReference(columns, value);
        return columns;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private Set<String> referencedColumns(Map<String, Object> config) {
        Set<String> refs = new LinkedHashSet<>();
        addColumnReference(refs, config.get("column"));
        addColumnReference(refs, config.get("columns"));
        addColumnReference(refs, config.get("requiredColumns"));
        addColumnReference(refs, config.get("keys"));
        addColumnReference(refs, config.get("groupBy"));
        addColumnReference(refs, config.get("partitionBy"));
        addColumnReference(refs, config.get("orderBy"));
        addColumnReference(refs, config.get("uniqueKey"));
        addColumnReference(refs, config.get("incrementalColumn"));
        if (config.get("mapping") instanceof Map<?, ?> mapping) {
            for (Object source : mapping.keySet()) {
                addColumnReference(refs, source);
            }
        }
        if (config.get("mappings") instanceof List<?> mappings) {
            for (Object item : mappings) {
                if (item instanceof Map<?, ?> mapping) {
                    addColumnReference(refs, mapping.get("source"));
                }
            }
        }
        return refs;
    }

    private void addColumnReference(Set<String> refs, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> values) {
            for (Object item : values) {
                addColumnReference(refs, item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                addColumnReference(refs, item);
            }
            return;
        }
        String raw = textValue(value);
        if (raw == null) {
            return;
        }
        if (raw.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
            refs.add(normalizeColumn(raw));
            return;
        }
        var matcher = IDENTIFIER_PATTERN.matcher(raw);
        while (matcher.find()) {
            String token = matcher.group();
            String normalized = normalizeColumn(token);
            if (normalized != null && !FIELD_REF_STOP_WORDS.contains(normalized)) {
                refs.add(normalized);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private SchemaStep deriveNodeSchema(
        String nodeId,
        String operatorRef,
        String nodeType,
        Map<String, Object> config,
        Set<String> currentSchema,
        Set<String> sensitiveColumns,
        List<String> warnings
    ) {
        Set<String> nextSchema = new LinkedHashSet<>(currentSchema);
        Set<String> nextSensitive = new LinkedHashSet<>(sensitiveColumns);
        if ("INPUT".equalsIgnoreCase(String.valueOf(nodeType))) {
            Set<String> inputColumns = columnNames(config.get("columns"));
            if (!inputColumns.isEmpty()) {
                return new SchemaStep(inputColumns, intersect(nextSensitive, inputColumns));
            }
            return new SchemaStep(nextSchema, nextSensitive);
        }
        if ("transform.select_columns".equals(operatorRef)) {
            Set<String> selected = columnNames(config.get("columns"));
            if (!selected.isEmpty()) {
                return new SchemaStep(selected, intersect(nextSensitive, selected));
            }
        }
        if ("transform.rename_columns".equals(operatorRef) && config.get("mapping") instanceof Map<?, ?> rawMapping) {
            Map<String, String> mapping = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMapping.entrySet()) {
                String source = normalizeColumn(textValue(entry.getKey()));
                String target = normalizeColumn(textValue(entry.getValue()));
                if (source != null && target != null) {
                    mapping.put(source, target);
                }
            }
            if (nextSchema.isEmpty()) {
                warnings.add("节点 " + nodeId + " 无上游字段 schema，按 mapping 目标字段继续自一致校验");
                nextSchema.addAll(mapping.values());
            } else {
                for (Map.Entry<String, String> entry : mapping.entrySet()) {
                    if (nextSchema.remove(entry.getKey())) {
                        nextSchema.add(entry.getValue());
                    }
                }
            }
            nextSensitive = renameSensitiveColumns(nextSensitive, mapping);
            return new SchemaStep(nextSchema, nextSensitive);
        }
        if (config.get("mappings") instanceof List<?> rawMappings) {
            Set<String> targets = new LinkedHashSet<>();
            Map<String, String> mapping = new LinkedHashMap<>();
            for (Object item : rawMappings) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                String source = normalizeColumn(textValue(raw.get("source")));
                String target = normalizeColumn(textValue(raw.get("target")));
                if (source != null && target != null) {
                    mapping.put(source, target);
                    targets.add(target);
                }
            }
            if (!targets.isEmpty()) {
                nextSensitive = renameSensitiveColumns(nextSensitive, mapping);
                return new SchemaStep(targets, intersect(nextSensitive, targets));
            }
        }
        Set<String> produced = producedColumns(config);
        if (!produced.isEmpty()) {
            nextSchema.addAll(produced);
        }
        return new SchemaStep(nextSchema, nextSensitive);
    }

    private Set<String> producedColumns(Map<String, Object> config) {
        Set<String> produced = new LinkedHashSet<>();
        addProducedColumn(produced, config.get("name"));
        addProducedColumn(produced, config.get("as"));
        produced.addAll(columnNames(config.get("outputs")));
        return produced;
    }

    private void addProducedColumn(Set<String> produced, Object value) {
        String column = normalizeColumn(textValue(value));
        if (column != null) {
            produced.add(column);
        }
    }

    private Set<String> renameSensitiveColumns(Set<String> sensitiveColumns, Map<String, String> mapping) {
        Set<String> renamed = new LinkedHashSet<>(sensitiveColumns);
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (renamed.remove(entry.getKey())) {
                renamed.add(entry.getValue());
            }
        }
        return renamed;
    }

    private Set<String> intersect(Set<String> values, Set<String> allowed) {
        Set<String> intersection = new LinkedHashSet<>(values);
        intersection.retainAll(allowed);
        return intersection;
    }

    private boolean isProtectingOperator(String nodeType, String operatorRef) {
        return "MASK".equalsIgnoreCase(String.valueOf(nodeType))
            || "ENCRYPT".equalsIgnoreCase(String.valueOf(nodeType))
            || (operatorRef != null && (operatorRef.startsWith("mask.") || operatorRef.startsWith("encrypt.")));
    }

    private Set<String> governedColumns(Map<String, Object> config, Set<String> currentSchema) {
        Set<String> columns = new LinkedHashSet<>();
        addColumnReference(columns, config.get("column"));
        addColumnReference(columns, config.get("columns"));
        if (columns.isEmpty()) {
            columns.addAll(currentSchema);
        }
        return columns;
    }

    private boolean isSensitiveLevel(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("L3") || normalized.equals("L4") || normalized.equals("L5")
            || normalized.contains("SENSITIVE") || normalized.contains("PII");
    }

    private String normalizeColumn(String value) {
        String text = textValue(value);
        if (text == null) {
            return null;
        }
        if (text.contains(".")) {
            text = text.substring(text.lastIndexOf('.') + 1);
        }
        text = text.replace("\"", "").replace("`", "").trim().toLowerCase(Locale.ROOT);
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private void validateExecutionResourceContract(
        Map<String, Object> graph,
        List<Map<String, Object>> nodes,
        List<String> errors,
        List<String> warnings
    ) {
        String engine = firstText(textValue(graph.get("engine")), "SPARK").toUpperCase(Locale.ROOT);
        String resourceGroup = textValue(graph.get("resourceGroup"));
        String computeProfile = textValue(graph.get("computeProfile"));
        if (!"SPARK".equals(engine)) {
            errors.add("operator graph engine=" + engine + " 不在 Spark-only 执行闭环内");
        }
        if (resourceGroup != null) {
            validateResourceGroup(engine, resourceGroup, errors);
        } else {
            warnings.add("operator graph 未声明 resourceGroup，运行时将使用 default");
        }
        if (computeProfile != null) {
            validateComputeProfile(firstText(resourceGroup, "default"), computeProfile, errors);
        }

        for (Map<String, Object> node : nodes) {
            if (!(node.get("resourceProfile") instanceof Map<?, ?> rawProfile)) {
                continue;
            }
            String nodeId = textValue(node.get("id"));
            Map<String, Object> profile = (Map<String, Object>) rawProfile;
            String nodeResourceGroup = textValue(profile.get("resourceGroup"));
            String nodeComputeProfile = textValue(profile.get("computeProfile"));
            if (nodeResourceGroup != null) {
                validateResourceGroup(engine, nodeResourceGroup, errors);
            }
            if (nodeComputeProfile != null) {
                validateComputeProfile(firstText(nodeResourceGroup, firstText(resourceGroup, "default")),
                    nodeComputeProfile, errors);
            }
            if (resourceGroup != null && nodeResourceGroup != null && !resourceGroup.equals(nodeResourceGroup)) {
                warnings.add("节点 " + nodeId + " resourceProfile.resourceGroup 与图级 resourceGroup 不一致: "
                    + nodeResourceGroup + " != " + resourceGroup);
            }
        }
    }

    private void validateResourceGroup(String engine, String resourceGroup, List<String> errors) {
        String normalizedEngine = engine == null ? "SPARK" : engine.toUpperCase(Locale.ROOT);
        if (!resourceGroupService.supportsResourceGroup(normalizedEngine, resourceGroup)) {
            errors.add("resourceGroup 不存在或不支持当前 engine: " + resourceGroup + "/" + normalizedEngine);
        }
    }

    private void validateComputeProfile(String resourceGroup, String computeProfile, List<String> errors) {
        if (!resourceGroupService.supportsComputeProfile(resourceGroup, computeProfile)) {
            errors.add("computeProfile 不存在或不属于当前 resourceGroup: " + computeProfile + "/" + resourceGroup);
        }
    }

    private void validateTargetPorts(
        String nodeId,
        List<Map<String, Object>> inboundEdges,
        Map<String, String> portCardinality,
        List<String> errors
    ) {
        if (portCardinality.isEmpty()) {
            return;
        }
        Map<String, Integer> countByPort = new LinkedHashMap<>();
        for (Map<String, Object> edge : inboundEdges) {
            String targetPort = textValue(edge.get("targetPort"));
            if (targetPort == null && portCardinality.size() == 1) {
                targetPort = portCardinality.keySet().iterator().next();
            }
            if (targetPort == null) {
                errors.add("节点 " + nodeId + " 存在多输入端口，边必须声明 targetPort");
                continue;
            }
            if (!portCardinality.containsKey(targetPort)) {
                errors.add("节点 " + nodeId + " 不存在输入端口: " + targetPort);
                continue;
            }
            countByPort.put(targetPort, countByPort.getOrDefault(targetPort, 0) + 1);
        }
        for (Map.Entry<String, String> entry : portCardinality.entrySet()) {
            String port = entry.getKey();
            String cardinality = entry.getValue();
            int count = countByPort.getOrDefault(port, 0);
            if (count == 0) {
                errors.add("节点 " + nodeId + " 输入端口 " + port + " 缺少输入边");
            }
            if (!"MANY".equalsIgnoreCase(cardinality) && count > 1) {
                errors.add("节点 " + nodeId + " 输入端口 " + port + " 最多允许 1 条输入边");
            }
        }
    }

    private boolean hasCycle(Map<String, List<String>> outgoing) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : outgoing.keySet()) {
            if (detectCycle(node, outgoing, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean detectCycle(
        String node,
        Map<String, List<String>> outgoing,
        Set<String> visiting,
        Set<String> visited
    ) {
        if (visited.contains(node)) {
            return false;
        }
        if (!visiting.add(node)) {
            return true;
        }
        for (String next : outgoing.getOrDefault(node, List.of())) {
            if (detectCycle(next, outgoing, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private boolean matchesKeyword(Operator operator, String keyword) {
        if (keyword == null) {
            return true;
        }
        return contains(operator.getOperatorRef(), keyword)
            || contains(operator.getDisplayName(), keyword)
            || contains(operator.getDescription(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String normalizeKeyword(String keyword) {
        String value = blankToNull(keyword);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String textValue(Object value) {
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    private String firstText(String first, String second) {
        return first != null ? first : second;
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        return tenantId;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new BizException(40033, type.getSimpleName() + " 不能为空");
            }
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (required) {
                throw new BizException(40034, type.getSimpleName() + " 不支持: " + value);
            }
            return null;
        }
    }

    public static Map<String, Object> auditDetail(Object... kv) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            detail.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return detail;
    }

    private record FieldGraphContext(
        Set<String> sourceColumns,
        Set<String> outputColumns,
        Set<String> sensitiveColumns,
        boolean hasGovernanceMeta
    ) {
    }

    private record ColumnGovernance(String classification, String piiType, String suggestLevel) {
    }

    private record SchemaStep(Set<String> columns, Set<String> sensitiveColumns) {
    }
}

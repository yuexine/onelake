package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.system.repository.TenantRepository;
import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.dto.ParamDTO;
import com.onelake.orchestration.dto.ParamReplaceRequest;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 租户全局、流水线和节点参数的查询、整组替换与保存校验。 */
@Service
@RequiredArgsConstructor
public class PipelineParamService {

    private static final String GLOBAL = "GLOBAL";
    private static final String PIPELINE = "PIPELINE";
    private static final String TASK = "TASK";
    private static final Set<String> VALUE_TYPES = Set.of("STRING", "NUMBER", "BOOL", "EXPR");
    private static final Pattern PARAM_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final PipelineParamRepository paramRepo;
    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final TenantRepository tenantRepo;

    /** 查询当前租户的全部全局参数。 */
    @Transactional(readOnly = true)
    public List<ParamDTO> listGlobalParams() {
        return toDtos(paramRepo.findByTenantIdAndScope(requireTenant(), GLOBAL));
    }

    /** 查询当前租户指定流水线的流水线级和全部节点级参数。 */
    @Transactional(readOnly = true)
    public List<ParamDTO> listPipelineParams(UUID dagId) {
        UUID tenantId = requireTenant();
        requireDag(dagId, tenantId);
        List<PipelineParam> params = new ArrayList<>();
        params.addAll(paramRepo.findByTenantIdAndDagIdAndScope(tenantId, dagId, PIPELINE));
        params.addAll(paramRepo.findByTenantIdAndDagIdAndScope(tenantId, dagId, TASK));
        return toDtos(params);
    }

    /** 用请求中的完整列表替换当前租户全局参数；空列表表示清空。 */
    @Transactional
    public List<ParamDTO> replaceGlobalParams(List<ParamDTO> requested) {
        UUID tenantId = requireTenant();
        List<PipelineParam> replacement = validateAndMap(
                requested, tenantId, null, Set.of(), Set.of(GLOBAL));
        lockTenant(tenantId);
        List<PipelineParam> existing = paramRepo.findByTenantIdAndScope(tenantId, GLOBAL);
        if (!existing.isEmpty()) {
            paramRepo.deleteAllInBatch(existing);
        }
        return toDtos(paramRepo.saveAllAndFlush(replacement));
    }

    /** 只替换请求指定的 PIPELINE 或单个 TASK 参数集合，避免覆盖其他编辑器的快照。 */
    @Transactional
    public List<ParamDTO> replacePipelineParams(UUID dagId, ParamReplaceRequest request) {
        UUID tenantId = requireTenant();
        requireDag(dagId, tenantId);
        if (request == null || !StringUtils.hasText(request.scope())) {
            throw new BizException(40041, "参数替换 scope 不能为空");
        }
        if (request.params() == null) {
            throw new BizException(40040, "参数替换 params 不能为空；清空请传空数组");
        }
        String scope = normalize(request.scope(), null);
        if (!Set.of(PIPELINE, TASK).contains(scope)) {
            throw new BizException(40041, "参数替换 scope 仅支持 PIPELINE 或 TASK");
        }

        String taskKey = null;
        Set<String> taskKeys = Set.of();
        if (TASK.equals(scope)) {
            String requestedTaskKey = request.taskKey() == null ? "" : request.taskKey().trim();
            var task = taskRepo.findByDagIdAndTaskKeyForUpdate(dagId, requestedTaskKey)
                    .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                    .orElseThrow(() -> new BizException(
                            40043, "节点级参数必须引用当前流水线中的有效 taskKey: " + requestedTaskKey));
            taskKey = requestedTaskKey;
            taskKeys = Set.of(task.getTaskKey());
        } else if (StringUtils.hasText(request.taskKey())) {
            throw new BizException(40043, "PIPELINE 参数替换不允许携带 taskKey");
        }

        String targetTaskKey = taskKey;
        List<ParamDTO> normalizedItems = request.params().stream()
                .map(item -> item == null ? null : new ParamDTO(
                        item.id(), scope, dagId, targetTaskKey,
                        item.paramKey(), item.paramValue(), item.valueType(),
                        item.description(), item.updatedAt()))
                .toList();
        List<PipelineParam> replacement = validateAndMap(
                normalizedItems, tenantId, dagId, taskKeys, Set.of(scope));

        if (PIPELINE.equals(scope)) {
            lockDag(dagId, tenantId);
        }

        List<PipelineParam> existing = PIPELINE.equals(scope)
                ? paramRepo.findByTenantIdAndDagIdAndScope(tenantId, dagId, PIPELINE)
                : paramRepo.findByTenantIdAndDagIdAndTaskKeyAndScope(
                        tenantId, dagId, targetTaskKey, TASK);
        if (!existing.isEmpty()) {
            paramRepo.deleteAllInBatch(existing);
        }
        return toDtos(paramRepo.saveAllAndFlush(replacement));
    }

    private List<PipelineParam> validateAndMap(List<ParamDTO> requested,
                                               UUID tenantId,
                                               UUID dagId,
                                               Set<String> taskKeys,
                                               Set<String> allowedScopes) {
        List<ParamDTO> safeRequested = requested == null ? List.of() : requested;
        Set<String> identities = new HashSet<>();
        List<PipelineParam> result = new ArrayList<>(safeRequested.size());
        for (int index = 0; index < safeRequested.size(); index++) {
            ParamDTO item = safeRequested.get(index);
            if (item == null) {
                throw new BizException(40040, "参数列表第 " + (index + 1) + " 项不能为空");
            }
            String scope = normalize(item.scope(), allowedScopes.size() == 1 ? GLOBAL : PIPELINE);
            if (!allowedScopes.contains(scope)) {
                throw new BizException(40041, "参数 scope 不受当前接口支持: " + scope);
            }
            String key = item.paramKey() == null ? "" : item.paramKey().trim();
            if (key.length() > 128 || !PARAM_KEY.matcher(key).matches()) {
                throw new BizException(40042,
                        "参数 key 非法: " + key + "；需以字母或下划线开头，仅允许字母、数字、下划线、点和连字符");
            }
            if (RunContext.RESERVED_PARAM_KEYS.contains(key)
                    || ParamRenderer.isReservedExpressionKey(key)) {
                throw new BizException(40042, "参数 key 属于运行时保留名称，不能自定义: " + key);
            }
            String taskKey = null;
            if (TASK.equals(scope)) {
                taskKey = item.taskKey() == null ? "" : item.taskKey().trim();
                if (!StringUtils.hasText(taskKey) || !taskKeys.contains(taskKey)) {
                    throw new BizException(40043, "节点级参数必须引用当前流水线中的有效 taskKey: " + taskKey);
                }
            }
            String identity = scope + "\u0000" + (taskKey == null ? "" : taskKey) + "\u0000" + key;
            if (!identities.add(identity)) {
                throw new BizException(40044, "同一作用域内参数 key 重复: " + key);
            }

            String valueType = normalize(item.valueType(), "STRING");
            if (!VALUE_TYPES.contains(valueType)) {
                throw new BizException(40045, "valueType 仅支持 STRING、NUMBER、BOOL 或 EXPR");
            }
            String value = normalizeValue(key, item.paramValue(), valueType);
            String description = item.description() == null ? null : item.description().trim();
            if (description != null && description.length() > 512) {
                throw new BizException(40046, "参数 description 长度不能超过 512: " + key);
            }

            PipelineParam param = new PipelineParam();
            param.setTenantId(tenantId);
            param.setScope(scope);
            param.setDagId(dagId);
            param.setTaskKey(taskKey);
            param.setParamKey(key);
            param.setParamValue(value);
            param.setValueType(valueType);
            param.setDescription(StringUtils.hasText(description) ? description : null);
            param.setCreatedAt(Instant.now());
            param.setUpdatedAt(Instant.now());
            result.add(param);
        }
        return result;
    }

    private String normalizeValue(String key, String value, String valueType) {
        if ("STRING".equals(valueType)) {
            return value;
        }
        String normalized = value == null ? "" : value.trim();
        if ("NUMBER".equals(valueType)) {
            try {
                new BigDecimal(normalized);
                return normalized;
            } catch (NumberFormatException ex) {
                throw new BizException(40047, "NUMBER 参数值非法: " + key, ex);
            }
        }
        if ("BOOL".equals(valueType)) {
            if (!"true".equalsIgnoreCase(normalized) && !"false".equalsIgnoreCase(normalized)) {
                throw new BizException(40047, "BOOL 参数值仅支持 true 或 false: " + key);
            }
            return normalized.toLowerCase(Locale.ROOT);
        }
        if (!StringUtils.hasText(normalized)) {
            throw new BizException(40048, "EXPR 参数值不能为空: " + key);
        }
        try {
            ParamRenderer.validate(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new BizException(40048, "EXPR 参数无法解析: " + key + "；" + ex.getMessage(), ex);
        }
    }

    private List<ParamDTO> toDtos(List<PipelineParam> params) {
        return params.stream()
                .sorted(Comparator.comparing(PipelineParam::getScope)
                        .thenComparing(param -> param.getTaskKey() == null ? "" : param.getTaskKey())
                        .thenComparing(PipelineParam::getParamKey))
                .map(ParamDTO::of)
                .toList();
    }

    private void requireDag(UUID dagId, UUID tenantId) {
        if (dagId == null || dagRepo.findByIdAndTenantId(dagId, tenantId).isEmpty()) {
            throw new BizException(40400, "Pipeline 不存在");
        }
    }

    private void lockDag(UUID dagId, UUID tenantId) {
        dagRepo.findByIdForUpdate(dagId)
                .filter(dag -> tenantId.equals(dag.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
    }

    private void lockTenant(UUID tenantId) {
        tenantRepo.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new BizException(40100, "Tenant context required"));
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "Tenant context required");
        }
        return tenantId;
    }

    private String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }
}

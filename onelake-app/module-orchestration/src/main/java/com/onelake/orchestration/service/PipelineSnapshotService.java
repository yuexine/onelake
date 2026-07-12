package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.domain.entity.PipelineVersion;
import com.onelake.orchestration.domain.enums.EdgeLayer;
import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskType;
import com.onelake.orchestration.dto.PipelineVersionDetailDTO;
import com.onelake.orchestration.dto.PipelineVersionSummaryDTO;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import com.onelake.orchestration.repository.PipelineVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 组装、发布并读取流水线不可变 JSON 快照。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineSnapshotService {

    private static final Duration DAGSTER_GRAPH_ACTIVATION_TTL = Duration.ofMinutes(5);

    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final PipelineParamRepository paramRepo;
    private final PipelineVersionRepository versionRepo;

    /** 生成字段和集合顺序稳定的规范化 JSON 及 SHA-256 checksum。 */
    @Transactional(readOnly = true)
    public SnapshotPayload snapshot(UUID dagId) {
        UUID tenantId = requireTenant();
        Dag dag = dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
        return snapshot(dag);
    }

    /**
     * 发布当前草稿；相同 checksum 复用历史版本，否则写入 DAG 内递增的新版本。
     */
    @Transactional
    public PipelineVersion publishSnapshot(UUID dagId) {
        return publishSnapshot(dagId, null);
    }

    /** 发布调用方已经校验过的规范化快照，避免审批校验后再次读取可变草稿。 */
    @Transactional
    public PipelineVersion publishSnapshot(UUID dagId, SnapshotPayload verifiedPayload) {
        UUID tenantId = requireTenant();
        Dag dag = dagRepo.findByIdForUpdate(dagId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
        SnapshotPayload payload = verifiedPayload == null ? snapshot(dag) : verifiedPayload;
        PipelineVersion version = versionRepo
                .findFirstByDagIdAndChecksumOrderByVersionDesc(dagId, payload.checksum())
                .filter(candidate -> dag.getPublishedVersionId() != null
                        && dag.getPublishedVersionId().equals(candidate.getId()))
                .orElseGet(() -> createVersion(dag, payload));
        dag.setPublishedVersionId(version.getId());
        dag.setHasUnpublishedChanges(false);
        dagRepo.save(dag);
        return version;
    }

    /** 按版本号倒序查询当前租户可见的发布历史。 */
    @Transactional(readOnly = true)
    public List<PipelineVersionSummaryDTO> listVersions(UUID dagId) {
        Dag dag = requireTenantDag(dagId);
        return versionRepo.findByDagIdOrderByVersionDesc(dag.getId()).stream()
                .filter(version -> dag.getTenantId().equals(version.getTenantId()))
                .map(this::toSummary)
                .toList();
    }

    /** 按 DAG 内版本号读取完整不可变快照。 */
    @Transactional(readOnly = true)
    public PipelineVersionDetailDTO getVersion(UUID dagId, Integer versionNumber) {
        Dag dag = requireTenantDag(dagId);
        PipelineVersion version = versionRepo.findByDagIdAndVersion(dagId, versionNumber)
                .filter(candidate -> dag.getTenantId().equals(candidate.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 版本不存在"));
        try {
            return new PipelineVersionDetailDTO(
                    version.getId(), version.getDagId(), version.getVersion(), version.getChecksum(),
                    version.getStatus(), version.getNote(), version.getPublishedBy(),
                    version.getPublishedByName(), version.getCreatedAt(),
                    JsonUtil.mapper().readTree(version.getSnapshot()));
        } catch (Exception ex) {
            throw new BizException(50000, "Pipeline 版本快照无法解析: " + version.getId(), ex);
        }
    }

    /** 批量解析运行记录中的版本 ID，避免运行列表逐行查询版本号。 */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> versionNumbers(Set<UUID> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        UUID tenantId = requireTenant();
        return versionRepo.findAllById(versionIds).stream()
                .filter(version -> tenantId.equals(version.getTenantId()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        PipelineVersion::getId, PipelineVersion::getVersion));
    }

    /** 按版本 ID 读取并重建一次运行所需的 DAG、任务、边和冻结参数。 */
    @Transactional(readOnly = true)
    public ExecutionSnapshot loadExecutionSnapshot(UUID versionId, UUID expectedDagId) {
        return loadExecutionSnapshotForRuntime(versionId, expectedDagId, requireTenant());
    }

    /**
     * 内部执行器/回调按已持久化运行归属读取冻结快照，不依赖请求线程的用户上下文。
     * 调用方必须同时提供 tenantId 和 dagId，避免内部令牌路径扩大快照读取边界。
     */
    @Transactional(readOnly = true)
    ExecutionSnapshot loadExecutionSnapshotForRuntime(UUID versionId,
                                                      UUID expectedDagId,
                                                      UUID expectedTenantId) {
        if (expectedTenantId == null || expectedDagId == null) {
            throw new BizException(40100, "Pipeline runtime snapshot identity required");
        }
        PipelineVersion version = versionRepo.findById(versionId)
                .filter(candidate -> expectedTenantId.equals(candidate.getTenantId()))
                .filter(candidate -> expectedDagId.equals(candidate.getDagId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 版本不存在"));
        return decode(version);
    }

    /** 按 DAG 内版本号读取并重建快照，供版本回滚覆盖 DEV 草稿。 */
    @Transactional(readOnly = true)
    public ExecutionSnapshot loadExecutionSnapshot(UUID dagId, Integer versionNumber) {
        Dag dag = requireTenantDag(dagId);
        PipelineVersion version = versionRepo.findByDagIdAndVersion(dagId, versionNumber)
                .filter(candidate -> dag.getTenantId().equals(candidate.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 版本不存在"));
        return decode(version);
    }

    /**
     * 在独立事务中激活一次即将执行的版本，使随后发生的 Dagster reload 能读取该拓扑。
     * 独立事务确保调用方尚未提交 JobRun 时，code location 的回调请求也能看到租约。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activateForDagster(UUID versionId) {
        if (versionId == null) {
            return;
        }
        UUID tenantId = requireTenant();
        PipelineVersion version = versionRepo.findById(versionId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 版本不存在"));
        versionRepo.activateDagsterGraphVersion(
                version.getId(), Instant.now().plus(DAGSTER_GRAPH_ACTIVATION_TTL));
    }

    /** Dagster code location reload 使用；只构造当前发布版本和短期按需激活版本。 */
    @Transactional
    public List<ExecutionSnapshot> listExecutionSnapshotsForDagster() {
        Instant now = Instant.now();
        versionRepo.deleteExpiredDagsterGraphActivations(now);
        List<ExecutionSnapshot> snapshots = new ArrayList<>();
        for (PipelineVersion version : versionRepo.findDagsterGraphDefinitionVersions(now)) {
            try {
                snapshots.add(decode(version));
            } catch (BizException ex) {
                log.warn("跳过无法解析的 Pipeline graph 版本 versionId={} dagId={}：{}",
                        version.getId(), version.getDagId(), ex.getMessage());
            }
        }
        return List.copyOf(snapshots);
    }

    private ExecutionSnapshot decode(PipelineVersion version) {
        try {
            JsonNode root = JsonUtil.mapper().readTree(version.getSnapshot());
            Dag dag = readDag(root.path("dag"), root.path("schedule"), version);
            List<PipelineTask> tasks = readTasks(root.path("tasks"), version);
            List<PipelineTaskEdge> edges = readEdges(root.path("edges"), version);
            List<PipelineParam> params = readParams(root.path("pipeline_params"), version);
            return new ExecutionSnapshot(version, dag, List.copyOf(tasks), List.copyOf(edges), List.copyOf(params));
        } catch (Exception ex) {
            throw new BizException(50000, "Pipeline 版本快照无法解析: " + version.getId(), ex);
        }
    }

    private SnapshotPayload snapshot(Dag dag) {
        List<PipelineTask> tasks = new ArrayList<>(taskRepo.findByDagIdOrderByCreatedAtAsc(dag.getId()));
        tasks.sort(Comparator.comparing(PipelineTask::getTaskKey));
        List<PipelineTaskEdge> edges = new ArrayList<>(edgeRepo.findByDagId(dag.getId()));
        edges.sort(Comparator.comparing(PipelineTaskEdge::getSourceKey)
                .thenComparing(PipelineTaskEdge::getTargetKey)
                .thenComparing(edge -> value(edge.getEdgeLayer()))
                .thenComparing(edge -> value(edge.getSourcePort()))
                .thenComparing(edge -> value(edge.getTargetPort()))
                .thenComparing(edge -> value(edge.getId())));
        List<PipelineParam> params = new ArrayList<>();
        params.addAll(paramRepo.findByTenantIdAndScope(dag.getTenantId(), "GLOBAL"));
        params.addAll(paramRepo.findByTenantIdAndDagIdAndScope(dag.getTenantId(), dag.getId(), "PIPELINE"));
        params.addAll(paramRepo.findByTenantIdAndDagIdAndScope(dag.getTenantId(), dag.getId(), "TASK"));
        params.sort(Comparator.comparing(PipelineParam::getScope)
                .thenComparing(param -> value(param.getTaskKey()))
                .thenComparing(PipelineParam::getParamKey)
                .thenComparing(param -> value(param.getId())));

        ObjectNode root = JsonUtil.mapper().createObjectNode();
        root.set("dag", dagNode(dag));
        ArrayNode taskNodes = root.putArray("tasks");
        tasks.forEach(task -> taskNodes.add(taskNode(task)));
        ArrayNode edgeNodes = root.putArray("edges");
        edges.forEach(edge -> edgeNodes.add(edgeNode(edge)));
        ArrayNode paramNodes = root.putArray("pipeline_params");
        params.forEach(param -> paramNodes.add(paramNode(param)));
        root.set("schedule", scheduleNode(dag));

        String json = canonicalize(root).toString();
        return new SnapshotPayload(json, sha256(json));
    }

    private PipelineVersion createVersion(Dag dag, SnapshotPayload payload) {
        int nextVersion = versionRepo.findByDagIdOrderByVersionDesc(dag.getId()).stream()
                .map(PipelineVersion::getVersion)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        PipelineVersion version = new PipelineVersion();
        version.setTenantId(dag.getTenantId());
        version.setDagId(dag.getId());
        version.setVersion(nextVersion);
        version.setSnapshot(payload.json());
        version.setChecksum(payload.checksum());
        version.setStatus("PUBLISHED");
        version.setPublishedBy(TenantContext.getUserId());
        version.setPublishedByName(TenantContext.getUsername());
        version.setCreatedAt(Instant.now());
        return versionRepo.save(version);
    }

    private Dag requireTenantDag(UUID dagId) {
        UUID tenantId = requireTenant();
        return dagRepo.findByIdAndTenantId(dagId, tenantId)
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
    }

    private PipelineVersionSummaryDTO toSummary(PipelineVersion version) {
        return new PipelineVersionSummaryDTO(
                version.getId(), version.getDagId(), version.getVersion(), version.getChecksum(),
                version.getStatus(), version.getNote(), version.getPublishedBy(),
                version.getPublishedByName(), version.getCreatedAt());
    }

    private ObjectNode dagNode(Dag dag) {
        ObjectNode node = JsonUtil.mapper().createObjectNode();
        put(node, "id", dag.getId());
        put(node, "tenantId", dag.getTenantId());
        put(node, "name", dag.getName());
        put(node, "dagsterJob", dag.getDagsterJob());
        node.set("definition", jsonValue(dag.getDefinition()));
        put(node, "enabled", dag.getEnabled());
        put(node, "pipelineKind", dag.getPipelineKind());
        put(node, "engine", dag.getEngine());
        put(node, "resourceGroup", dag.getResourceGroup());
        put(node, "computeProfile", dag.getComputeProfile());
        put(node, "partitionGrain", dag.getPartitionGrain());
        return node;
    }

    private ObjectNode taskNode(PipelineTask task) {
        ObjectNode node = JsonUtil.mapper().createObjectNode();
        put(node, "id", task.getId());
        put(node, "tenantId", task.getTenantId());
        put(node, "dagId", task.getDagId());
        put(node, "taskKey", task.getTaskKey());
        put(node, "taskType", task.getTaskType() == null ? null : task.getTaskType().name());
        put(node, "category", task.getCategory() == null ? null : task.getCategory().name());
        put(node, "operatorRef", task.getOperatorRef());
        put(node, "operatorVersion", task.getOperatorVersion());
        put(node, "name", task.getName());
        put(node, "engine", task.getEngine());
        put(node, "targetFqn", task.getTargetFqn());
        put(node, "partitionKey", task.getPartitionKey());
        put(node, "partitionGrain", task.getPartitionGrain());
        put(node, "modelId", task.getModelId());
        put(node, "syncTaskId", task.getSyncTaskId());
        node.set("config", jsonValue(task.getConfig()));
        put(node, "positionX", task.getPositionX());
        put(node, "positionY", task.getPositionY());
        return node;
    }

    private ObjectNode edgeNode(PipelineTaskEdge edge) {
        ObjectNode node = JsonUtil.mapper().createObjectNode();
        put(node, "id", edge.getId());
        put(node, "tenantId", edge.getTenantId());
        put(node, "dagId", edge.getDagId());
        put(node, "sourceKey", edge.getSourceKey());
        put(node, "targetKey", edge.getTargetKey());
        put(node, "edgeLayer", edge.getEdgeLayer() == null ? null : edge.getEdgeLayer().name());
        put(node, "sourcePort", edge.getSourcePort());
        put(node, "targetPort", edge.getTargetPort());
        put(node, "sourceOutput", edge.getSourceOutput());
        put(node, "targetInput", edge.getTargetInput());
        put(node, "assetFqn", edge.getAssetFqn());
        put(node, "inputAlias", edge.getInputAlias());
        put(node, "joinRole", edge.getJoinRole());
        put(node, "triggerPolicy", edge.getTriggerPolicy());
        put(node, "freshnessPolicy", edge.getFreshnessPolicy());
        put(node, "auto", edge.getAuto());
        return node;
    }

    private ObjectNode paramNode(PipelineParam param) {
        ObjectNode node = JsonUtil.mapper().createObjectNode();
        put(node, "id", param.getId());
        put(node, "tenantId", param.getTenantId());
        put(node, "dagId", param.getDagId());
        put(node, "scope", param.getScope());
        put(node, "taskKey", param.getTaskKey());
        put(node, "paramKey", param.getParamKey());
        put(node, "paramValue", param.getParamValue());
        put(node, "valueType", param.getValueType());
        put(node, "description", param.getDescription());
        return node;
    }

    private ObjectNode scheduleNode(Dag dag) {
        ObjectNode node = JsonUtil.mapper().createObjectNode();
        put(node, "scheduleCron", dag.getScheduleCron());
        put(node, "timezone", dag.getTimezone());
        put(node, "catchup", dag.getCatchup());
        put(node, "maxActiveRuns", dag.getMaxActiveRuns());
        put(node, "priority", dag.getPriority());
        put(node, "slaMinutes", dag.getSlaMinutes());
        put(node, "timeoutMinutes", dag.getTimeoutMinutes());
        put(node, "scheduleMode", dag.getScheduleMode());
        put(node, "runRetryCount", dag.getRunRetryCount());
        put(node, "runRetryIntervalSeconds", dag.getRunRetryIntervalSeconds());
        put(node, "misfirePolicy", dag.getMisfirePolicy());
        put(node, "dependencyWaitTimeoutMinutes", dag.getDependencyWaitTimeoutMinutes());
        put(node, "calendarId", dag.getCalendarId());
        put(node, "scheduleStart", dag.getScheduleStart());
        put(node, "scheduleEnd", dag.getScheduleEnd());
        return node;
    }

    private Dag readDag(JsonNode node, JsonNode schedule, PipelineVersion version) {
        Dag dag = new Dag();
        dag.setId(version.getDagId());
        dag.setTenantId(version.getTenantId());
        dag.setName(text(node, "name"));
        dag.setDagsterJob(text(node, "dagsterJob"));
        dag.setDefinition(jsonText(node.path("definition"), "{}"));
        dag.setEnabled(bool(node, "enabled", true));
        dag.setVersion(version.getVersion());
        dag.setPipelineKind(text(node, "pipelineKind"));
        dag.setStatus("PUBLISHED");
        dag.setEngine(text(node, "engine"));
        dag.setResourceGroup(text(node, "resourceGroup"));
        dag.setComputeProfile(text(node, "computeProfile"));
        dag.setPartitionGrain(textOr(schedule, "partitionGrain", text(node, "partitionGrain")));
        dag.setScheduleCron(text(schedule, "scheduleCron"));
        dag.setTimezone(textOr(schedule, "timezone", "Asia/Shanghai"));
        dag.setCatchup(bool(schedule, "catchup", false));
        dag.setMaxActiveRuns(integer(schedule, "maxActiveRuns", 1));
        dag.setPriority(integer(schedule, "priority", 5));
        dag.setSlaMinutes(nullableInteger(schedule, "slaMinutes"));
        dag.setTimeoutMinutes(nullableInteger(schedule, "timeoutMinutes"));
        dag.setScheduleMode(textOr(schedule, "scheduleMode", "NORMAL"));
        dag.setRunRetryCount(integer(schedule, "runRetryCount", 0));
        dag.setRunRetryIntervalSeconds(integer(schedule, "runRetryIntervalSeconds", 0));
        dag.setMisfirePolicy(textOr(schedule, "misfirePolicy", "FIRE_ONCE"));
        dag.setDependencyWaitTimeoutMinutes(integer(schedule, "dependencyWaitTimeoutMinutes", 1440));
        dag.setCalendarId(uuid(schedule, "calendarId"));
        dag.setScheduleStart(instant(schedule, "scheduleStart"));
        dag.setScheduleEnd(instant(schedule, "scheduleEnd"));
        dag.setPublishedVersionId(version.getId());
        dag.setHasUnpublishedChanges(false);
        return dag;
    }

    private List<PipelineTask> readTasks(JsonNode nodes, PipelineVersion version) {
        List<PipelineTask> tasks = new ArrayList<>();
        if (!nodes.isArray()) return tasks;
        for (JsonNode node : nodes) {
            PipelineTask task = new PipelineTask();
            task.setId(uuid(node, "id"));
            task.setTenantId(version.getTenantId());
            task.setDagId(version.getDagId());
            task.setTaskKey(text(node, "taskKey"));
            task.setTaskType(TaskType.valueOf(text(node, "taskType")));
            String category = text(node, "category");
            task.setCategory(StringUtils.hasText(category)
                    ? com.onelake.orchestration.domain.enums.TaskCategory.valueOf(category)
                    : task.getTaskType().category());
            task.setOperatorRef(text(node, "operatorRef"));
            task.setOperatorVersion(text(node, "operatorVersion"));
            task.setName(text(node, "name"));
            task.setEngine(text(node, "engine"));
            task.setTargetFqn(text(node, "targetFqn"));
            task.setPartitionKey(text(node, "partitionKey"));
            task.setPartitionGrain(text(node, "partitionGrain"));
            task.setModelId(uuid(node, "modelId"));
            task.setSyncTaskId(uuid(node, "syncTaskId"));
            task.setConfig(jsonText(node.path("config"), "{}"));
            task.setCompileStatus(TaskCompileStatus.DRAFT);
            task.setExecutable(false);
            task.setPositionX(nullableInteger(node, "positionX"));
            task.setPositionY(nullableInteger(node, "positionY"));
            tasks.add(task);
        }
        return tasks;
    }

    private List<PipelineTaskEdge> readEdges(JsonNode nodes, PipelineVersion version) {
        List<PipelineTaskEdge> edges = new ArrayList<>();
        if (!nodes.isArray()) return edges;
        for (JsonNode node : nodes) {
            PipelineTaskEdge edge = new PipelineTaskEdge();
            edge.setId(uuid(node, "id"));
            edge.setTenantId(version.getTenantId());
            edge.setDagId(version.getDagId());
            edge.setSourceKey(text(node, "sourceKey"));
            edge.setTargetKey(text(node, "targetKey"));
            edge.setEdgeLayer(EdgeLayer.valueOf(text(node, "edgeLayer")));
            edge.setSourcePort(text(node, "sourcePort"));
            edge.setTargetPort(text(node, "targetPort"));
            edge.setSourceOutput(text(node, "sourceOutput"));
            edge.setTargetInput(text(node, "targetInput"));
            edge.setAssetFqn(text(node, "assetFqn"));
            edge.setInputAlias(text(node, "inputAlias"));
            edge.setJoinRole(text(node, "joinRole"));
            edge.setTriggerPolicy(text(node, "triggerPolicy"));
            edge.setFreshnessPolicy(text(node, "freshnessPolicy"));
            edge.setAuto(bool(node, "auto", false));
            edges.add(edge);
        }
        return edges;
    }

    private List<PipelineParam> readParams(JsonNode nodes, PipelineVersion version) {
        List<PipelineParam> params = new ArrayList<>();
        if (!nodes.isArray()) return params;
        for (JsonNode node : nodes) {
            PipelineParam param = new PipelineParam();
            param.setId(uuid(node, "id"));
            param.setTenantId(version.getTenantId());
            param.setDagId(version.getDagId());
            param.setScope(text(node, "scope"));
            param.setTaskKey(text(node, "taskKey"));
            param.setParamKey(text(node, "paramKey"));
            param.setParamValue(text(node, "paramValue"));
            param.setValueType(textOr(node, "valueType", "STRING"));
            param.setDescription(text(node, "description"));
            params.add(param);
        }
        return params;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode result = JsonUtil.mapper().createArrayNode();
            node.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        ObjectNode result = JsonUtil.mapper().createObjectNode();
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
        iterator.forEachRemaining(fields::add);
        fields.sort(Map.Entry.comparingByKey());
        fields.forEach(entry -> result.set(entry.getKey(), canonicalize(entry.getValue())));
        return result;
    }

    private JsonNode jsonValue(String json) {
        if (json == null || json.isBlank()) return JsonUtil.mapper().createObjectNode();
        try {
            return JsonUtil.mapper().readTree(json);
        } catch (Exception ex) {
            throw new BizException(40020, "Pipeline JSON 字段无法解析", ex);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void put(ObjectNode node, String field, Object value) {
        if (value == null) node.putNull(field);
        else if (value instanceof Boolean bool) node.put(field, bool);
        else if (value instanceof Integer number) node.put(field, number);
        else node.put(field, value.toString());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int integer(JsonNode node, String field, int fallback) {
        Integer value = nullableInteger(node, field);
        return value == null ? fallback : value;
    }

    private static Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.asInt() : null;
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String jsonText(JsonNode node, String fallback) {
        return node == null || node.isMissingNode() || node.isNull() ? fallback : node.toString();
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "Tenant context required");
        return tenantId;
    }

    public record SnapshotPayload(String json, String checksum) {}

    public record ExecutionSnapshot(
            PipelineVersion version,
            Dag dag,
            List<PipelineTask> tasks,
            List<PipelineTaskEdge> edges,
            List<PipelineParam> params) {}
}

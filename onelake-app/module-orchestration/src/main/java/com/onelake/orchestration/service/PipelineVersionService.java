package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.PipelineParam;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import com.onelake.orchestration.dto.PipelineVersionDetailDTO;
import com.onelake.orchestration.dto.PipelineVersionDiffDTO;
import com.onelake.orchestration.dto.PipelineVersionSummaryDTO;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.PipelineParamRepository;
import com.onelake.orchestration.repository.PipelineTaskEdgeRepository;
import com.onelake.orchestration.repository.PipelineTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/** Pipeline 发布版本的查询、结构化对比与草稿回滚。 */
@Service
@RequiredArgsConstructor
public class PipelineVersionService {

    private static final Set<String> TECHNICAL_FIELDS = Set.of("id", "tenantId", "dagId");
    private static final Set<String> TASK_IDENTITY_FIELDS = Set.of("taskKey");
    private static final Set<String> EDGE_IDENTITY_FIELDS = Set.of("sourceKey", "targetKey", "edgeLayer");
    private static final Set<String> PARAM_IDENTITY_FIELDS = Set.of("scope", "taskKey", "paramKey");

    private final PipelineSnapshotService snapshotService;
    private final DagRepository dagRepo;
    private final PipelineTaskRepository taskRepo;
    private final PipelineTaskEdgeRepository edgeRepo;
    private final PipelineParamRepository paramRepo;

    @Transactional(readOnly = true)
    public List<PipelineVersionSummaryDTO> listVersions(UUID dagId) {
        return snapshotService.listVersions(dagId);
    }

    @Transactional(readOnly = true)
    public PipelineVersionDetailDTO getVersion(UUID dagId, Integer version) {
        return snapshotService.getVersion(dagId, version);
    }

    /** 对任务、边和参数按业务身份比较，并输出新增、删除及字段级修改。 */
    @Transactional(readOnly = true)
    public PipelineVersionDiffDTO diff(UUID dagId, Integer fromVersion, Integer toVersion) {
        PipelineVersionDetailDTO from = snapshotService.getVersion(dagId, fromVersion);
        PipelineVersionDetailDTO to = snapshotService.getVersion(dagId, toVersion);
        JsonNode fromSnapshot = from.snapshot();
        JsonNode toSnapshot = to.snapshot();
        return new PipelineVersionDiffDTO(
                dagId,
                fromVersion,
                toVersion,
                diffCollection(fromSnapshot.path("tasks"), toSnapshot.path("tasks"),
                        node -> requiredText(node, "taskKey"), TASK_IDENTITY_FIELDS),
                diffCollection(fromSnapshot.path("edges"), toSnapshot.path("edges"),
                        this::edgeKey, EDGE_IDENTITY_FIELDS),
                diffCollection(fromSnapshot.path("pipeline_params"), toSnapshot.path("pipeline_params"),
                        this::paramKey, PARAM_IDENTITY_FIELDS));
    }

    /**
     * 用目标不可变快照覆盖当前 DEV 草稿；当前生产发布指针和历史版本均保持不变。
     * 租户 GLOBAL 参数不属于单条 Pipeline 草稿，因此不会被回滚。
     */
    @Transactional
    public void rollback(UUID dagId, Integer targetVersion) {
        UUID tenantId = requireTenant();
        Dag current = dagRepo.findByIdForUpdate(dagId)
                .filter(dag -> tenantId.equals(dag.getTenantId()))
                .orElseThrow(() -> new BizException(40400, "Pipeline 不存在"));
        PipelineSnapshotService.ExecutionSnapshot target =
                snapshotService.loadExecutionSnapshot(dagId, targetVersion);

        edgeRepo.deleteDraftByDagId(dagId);
        paramRepo.deleteDraftByTenantIdAndDagId(tenantId, dagId);
        taskRepo.deleteDraftByDagId(dagId);

        copyDraftFields(target.dag(), current);
        current.setHasUnpublishedChanges(true);
        dagRepo.save(current);

        List<UUID> taskSnapshotIds = target.tasks().stream().map(PipelineTask::getId).toList();
        List<PipelineTask> savedTasks = taskRepo.saveAllAndFlush(target.tasks());
        restoreTaskSnapshotIds(dagId, taskSnapshotIds, savedTasks);

        List<UUID> edgeSnapshotIds = target.edges().stream().map(PipelineTaskEdge::getId).toList();
        List<PipelineTaskEdge> savedEdges = edgeRepo.saveAllAndFlush(target.edges());
        restoreEdgeSnapshotIds(dagId, edgeSnapshotIds, savedEdges);

        List<PipelineParam> draftParams = target.params().stream()
                .filter(param -> "PIPELINE".equals(param.getScope()) || "TASK".equals(param.getScope()))
                .toList();
        List<UUID> paramSnapshotIds = draftParams.stream().map(PipelineParam::getId).toList();
        List<PipelineParam> savedParams = paramRepo.saveAllAndFlush(draftParams);
        restoreParamSnapshotIds(tenantId, dagId, paramSnapshotIds, savedParams);
    }

    private void restoreTaskSnapshotIds(UUID dagId,
                                        List<UUID> snapshotIds,
                                        List<PipelineTask> savedTasks) {
        requireSameSize("task", snapshotIds, savedTasks);
        for (int i = 0; i < snapshotIds.size(); i++) {
            UUID snapshotId = snapshotIds.get(i);
            UUID generatedId = savedTasks.get(i).getId();
            if (snapshotId == null || snapshotId.equals(generatedId)) continue;
            if (taskRepo.restoreSnapshotId(dagId, generatedId, snapshotId) != 1) {
                throw restoreIdFailure("task", generatedId, snapshotId);
            }
        }
    }

    private void restoreEdgeSnapshotIds(UUID dagId,
                                        List<UUID> snapshotIds,
                                        List<PipelineTaskEdge> savedEdges) {
        requireSameSize("edge", snapshotIds, savedEdges);
        for (int i = 0; i < snapshotIds.size(); i++) {
            UUID snapshotId = snapshotIds.get(i);
            UUID generatedId = savedEdges.get(i).getId();
            if (snapshotId == null || snapshotId.equals(generatedId)) continue;
            if (edgeRepo.restoreSnapshotId(dagId, generatedId, snapshotId) != 1) {
                throw restoreIdFailure("edge", generatedId, snapshotId);
            }
        }
    }

    private void restoreParamSnapshotIds(UUID tenantId,
                                         UUID dagId,
                                         List<UUID> snapshotIds,
                                         List<PipelineParam> savedParams) {
        requireSameSize("param", snapshotIds, savedParams);
        for (int i = 0; i < snapshotIds.size(); i++) {
            UUID snapshotId = snapshotIds.get(i);
            UUID generatedId = savedParams.get(i).getId();
            if (snapshotId == null || snapshotId.equals(generatedId)) continue;
            if (paramRepo.restoreSnapshotId(tenantId, dagId, generatedId, snapshotId) != 1) {
                throw restoreIdFailure("param", generatedId, snapshotId);
            }
        }
    }

    private void requireSameSize(String entity,
                                 List<UUID> snapshotIds,
                                 List<?> savedEntities) {
        if (snapshotIds.size() != savedEntities.size()) {
            throw new BizException(50000, "Pipeline 回滚保存 " + entity + " 数量不一致");
        }
    }

    private BizException restoreIdFailure(String entity, UUID generatedId, UUID snapshotId) {
        return new BizException(50000, "Pipeline 回滚恢复 " + entity + " 主键失败: "
                + generatedId + " -> " + snapshotId);
    }

    private PipelineVersionDiffDTO.CollectionDiff diffCollection(
            JsonNode beforeItems,
            JsonNode afterItems,
            Function<JsonNode, String> keyExtractor,
            Set<String> identityFields) {
        TreeMap<String, JsonNode> before = index(beforeItems, keyExtractor);
        TreeMap<String, JsonNode> after = index(afterItems, keyExtractor);
        List<PipelineVersionDiffDTO.ItemDiff> added = new ArrayList<>();
        List<PipelineVersionDiffDTO.ItemDiff> removed = new ArrayList<>();
        List<PipelineVersionDiffDTO.ItemDiff> changed = new ArrayList<>();

        for (String key : after.keySet()) {
            if (!before.containsKey(key)) {
                added.add(new PipelineVersionDiffDTO.ItemDiff(
                        key, null, after.get(key), List.of()));
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                removed.add(new PipelineVersionDiffDTO.ItemDiff(
                        key, before.get(key), null, List.of()));
            }
        }
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) continue;
            List<PipelineVersionDiffDTO.FieldChange> fields = fieldChanges(
                    before.get(key), after.get(key), identityFields);
            if (!fields.isEmpty()) {
                changed.add(new PipelineVersionDiffDTO.ItemDiff(
                        key, before.get(key), after.get(key), fields));
            }
        }
        return new PipelineVersionDiffDTO.CollectionDiff(
                List.copyOf(added), List.copyOf(removed), List.copyOf(changed));
    }

    private TreeMap<String, JsonNode> index(JsonNode items, Function<JsonNode, String> keyExtractor) {
        TreeMap<String, JsonNode> result = new TreeMap<>();
        if (items == null || !items.isArray()) return result;
        for (JsonNode item : items) {
            result.put(keyExtractor.apply(item), item);
        }
        return result;
    }

    private List<PipelineVersionDiffDTO.FieldChange> fieldChanges(
            JsonNode before,
            JsonNode after,
            Set<String> identityFields) {
        TreeSet<String> fields = new TreeSet<>();
        before.fieldNames().forEachRemaining(fields::add);
        after.fieldNames().forEachRemaining(fields::add);
        fields.removeAll(TECHNICAL_FIELDS);
        fields.removeAll(identityFields);
        return fields.stream()
                .filter(field -> !Objects.equals(before.get(field), after.get(field)))
                .map(field -> new PipelineVersionDiffDTO.FieldChange(
                        field, before.get(field), after.get(field)))
                .toList();
    }

    private String edgeKey(JsonNode node) {
        return requiredText(node, "sourceKey") + "->" + requiredText(node, "targetKey")
                + "[" + requiredText(node, "edgeLayer") + "]";
    }

    private String paramKey(JsonNode node) {
        return requiredText(node, "scope") + ":"
                + optionalText(node, "taskKey") + ":" + requiredText(node, "paramKey");
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) {
            throw new BizException(50000, "Pipeline 版本快照缺少结构身份字段: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private void copyDraftFields(Dag source, Dag target) {
        target.setName(source.getName());
        target.setDagsterJob(source.getDagsterJob());
        target.setDefinition(source.getDefinition());
        target.setEnabled(source.getEnabled());
        target.setPipelineKind(source.getPipelineKind());
        target.setEngine(source.getEngine());
        target.setResourceGroup(source.getResourceGroup());
        target.setComputeProfile(source.getComputeProfile());
        target.setPartitionGrain(source.getPartitionGrain());
        target.setScheduleCron(source.getScheduleCron());
        target.setTimezone(source.getTimezone());
        target.setCatchup(source.getCatchup());
        target.setMaxActiveRuns(source.getMaxActiveRuns());
        target.setPriority(source.getPriority());
        target.setSlaMinutes(source.getSlaMinutes());
        target.setTimeoutMinutes(source.getTimeoutMinutes());
        target.setScheduleMode(source.getScheduleMode());
        target.setRunRetryCount(source.getRunRetryCount());
        target.setRunRetryIntervalSeconds(source.getRunRetryIntervalSeconds());
        target.setMisfirePolicy(source.getMisfirePolicy());
        target.setDependencyWaitTimeoutMinutes(source.getDependencyWaitTimeoutMinutes());
        target.setCalendarId(source.getCalendarId());
        target.setScheduleStart(source.getScheduleStart());
        target.setScheduleEnd(source.getScheduleEnd());
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BizException(40100, "Tenant context required");
        return tenantId;
    }
}

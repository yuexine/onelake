package com.onelake.modeling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.domain.entity.DataModelColumnMapping;
import com.onelake.modeling.domain.entity.DataModelRun;
import com.onelake.modeling.domain.entity.DataModelSource;
import com.onelake.modeling.dto.DataModelDTO;
import com.onelake.modeling.dto.DwdModelCompileDTO;
import com.onelake.modeling.dto.DwdModelDraftRequest;
import com.onelake.modeling.dto.DwdModelRunDTO;
import com.onelake.modeling.dto.DwdModelRunRequest;
import com.onelake.modeling.dto.DwdModelValidationDTO;
import com.onelake.modeling.repository.DataModelColumnMappingRepository;
import com.onelake.modeling.repository.DataModelRepository;
import com.onelake.modeling.repository.DataModelRunRepository;
import com.onelake.modeling.repository.DataModelSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DwdModelService {

    private static final Pattern TABLE_NAME =
        Pattern.compile("^(ods|dwd|dws|ads)_([a-z]+)_([a-z_]+)_(df|di|dms|d|w|m|y|app)$");

    private final DataModelRepository modelRepo;
    private final DataModelSourceRepository sourceRepo;
    private final DataModelColumnMappingRepository mappingRepo;
    private final DataModelRunRepository runRepo;
    private final DwdModelDagsterClient dagsterClient;
    private final DwdRunArtifactReader artifactReader;
    private final OutboxPublisher outboxPublisher;
    private final JdbcTemplate jdbc;

    @Transactional
    public DataModelDTO createDraft(DwdModelDraftRequest request) {
        UUID tenantId = requireTenant();
        CatalogAsset source = loadCatalogAsset(tenantId, text(request.sourceFqn(), "sourceFqn"));
        requireOdsSource(source);

        String name = normalizeName(request.name(), request.targetFqn());
        String targetFqn = normalizeTargetFqn(request.targetFqn(), name);
        validateTargetName(name, targetFqn);
        modelRepo.findByTenantIdAndTargetFqn(tenantId, targetFqn).ifPresent(existing -> {
            throw new BizException(40930, "DWD 模型目标表已存在: " + targetFqn);
        });

        List<ColumnDraft> mappings = normalizeMappings(request.columnMappings(), source.columns());
        if (mappings.isEmpty()) {
            throw new BizException(40041, "DWD 模型字段映射不能为空");
        }

        DataModel model = new DataModel();
        model.setTenantId(tenantId);
        model.setName(name);
        model.setLayer("DWD");
        model.setDomain(StringUtils.hasText(request.domain()) ? request.domain().trim() : source.domain());
        model.setSourceFqn(source.fqn());
        model.setTargetFqn(targetFqn);
        model.setStatus("DRAFT");
        model.setMaterialization(normalizeMaterialization(request.materialization()));
        model.setUniqueKey(blankToNull(request.uniqueKey()));
        model.setIncrementalColumn(blankToNull(request.incrementalColumn()));
        model.setPartitionExpr(blankToNull(request.partitionExpr()));
        model.setDbtModelName(name);
        model.setDagsterJob("onelake_dbt_model_run");
        model.setPipelineMode("SYSTEM_GENERATED");
        model.setOperatorGraphVersion(1);
        model.setResourceGroup("default");
        model.setComputeProfile("trino-small");
        model.setEngine("TRINO_DBT");
        model.setCostPolicy(defaultCostPolicyJson());
        assignCurrentOwnerIfMissing(model);
        String compiledSql = compileSql(source.fqn(), mappings);
        model.setSqlText(compiledSql);
        model.setCompiledSql(compiledSql);
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        modelRepo.save(model);

        DataModelSource modelSource = new DataModelSource();
        modelSource.setModelId(model.getId());
        modelSource.setSourceFqn(source.fqn());
        modelSource.setSourceType("ODS_TABLE");
        modelSource.setSortNo(0);
        sourceRepo.save(modelSource);

        saveMappings(model.getId(), mappings);
        return toDTO(model);
    }

    @Transactional(readOnly = true)
    public DataModelDTO get(UUID id) {
        return toDTO(loadModel(id));
    }

    @Transactional(readOnly = true)
    public List<DataModelDTO> list(String sourceFqn, String targetFqn) {
        UUID tenantId = requireTenant();
        List<DataModel> models;
        if (StringUtils.hasText(sourceFqn)) {
            models = modelRepo.findByTenantIdAndSourceFqnOrderByCreatedAtDesc(tenantId, sourceFqn.trim());
        } else if (StringUtils.hasText(targetFqn)) {
            models = modelRepo.findByTenantIdAndTargetFqnOrderByCreatedAtDesc(tenantId, targetFqn.trim());
        } else {
            models = modelRepo.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return models.stream().map(this::toDTO).toList();
    }

    @Transactional
    public DataModelDTO update(UUID id, DwdModelDraftRequest request) {
        DataModel model = loadModel(id);
        if (!"DRAFT".equalsIgnoreCase(model.getStatus())) {
            throw new BizException(40042, "只有草稿模型可以编辑");
        }
        UUID tenantId = requireTenant();
        CatalogAsset source = loadCatalogAsset(tenantId, text(request.sourceFqn(), "sourceFqn"));
        requireOdsSource(source);
        String name = normalizeName(request.name(), request.targetFqn());
        String targetFqn = normalizeTargetFqn(request.targetFqn(), name);
        validateTargetName(name, targetFqn);
        modelRepo.findByTenantIdAndTargetFqn(tenantId, targetFqn)
            .filter(existing -> !existing.getId().equals(id))
            .ifPresent(existing -> {
                throw new BizException(40930, "DWD 模型目标表已存在: " + targetFqn);
            });

        List<ColumnDraft> mappings = normalizeMappings(request.columnMappings(), source.columns());
        if (mappings.isEmpty()) {
            throw new BizException(40041, "DWD 模型字段映射不能为空");
        }

        model.setName(name);
        model.setDomain(StringUtils.hasText(request.domain()) ? request.domain().trim() : source.domain());
        model.setSourceFqn(source.fqn());
        model.setTargetFqn(targetFqn);
        model.setMaterialization(normalizeMaterialization(request.materialization()));
        model.setUniqueKey(blankToNull(request.uniqueKey()));
        model.setIncrementalColumn(blankToNull(request.incrementalColumn()));
        model.setPartitionExpr(blankToNull(request.partitionExpr()));
        model.setDbtModelName(name);
        applyExecutionDefaults(model);
        String compiledSql = compileSql(source.fqn(), mappings);
        model.setSqlText(compiledSql);
        model.setCompiledSql(compiledSql);
        model.setUpdatedAt(Instant.now());

        sourceRepo.deleteByModelId(id);
        mappingRepo.deleteByModelId(id);

        DataModelSource modelSource = new DataModelSource();
        modelSource.setModelId(id);
        modelSource.setSourceFqn(source.fqn());
        modelSource.setSourceType("ODS_TABLE");
        modelSource.setSortNo(0);
        sourceRepo.save(modelSource);
        saveMappings(id, mappings);
        return toDTO(model);
    }

    @Transactional(readOnly = true)
    public DwdModelValidationDTO validate(UUID id) {
        DataModel model = loadModel(id);
        List<DataModelColumnMapping> mappings = mappingRepo.findByModelIdOrderBySortNoAsc(model.getId());
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            CatalogAsset source = loadCatalogAsset(model.getTenantId(), model.getSourceFqn());
            requireOdsSource(source);
            if (source.columns().isEmpty()) {
                errors.add("上游 ODS 资产字段为空");
            }
        } catch (BizException e) {
            errors.add(e.getMessage());
        }
        if (mappings.isEmpty()) {
            errors.add("字段映射为空");
        }
        if ("INCREMENTAL".equalsIgnoreCase(model.getMaterialization())
            && !StringUtils.hasText(model.getUniqueKey())
            && !StringUtils.hasText(model.getIncrementalColumn())) {
            errors.add("增量模型必须配置 uniqueKey 或 incrementalColumn");
        }
        long sensitivePassthrough = mappings.stream()
            .filter(m -> isSensitive(m.getClassification()) || isSensitive(m.getSuggestLevel()))
            .filter(m -> !isTransformExpression(m.getExpression()))
            .count();
        if (sensitivePassthrough > 0) {
            warnings.add("存在敏感字段直接透传，后续迭代需确认脱敏/加密策略");
        }

        String compiledSql = compileSql(model.getSourceFqn(), mappings.stream().map(this::toDraft).toList());
        return new DwdModelValidationDTO(
            errors.isEmpty(),
            errors,
            warnings,
            compiledSql,
            List.of(model.getSourceFqn()),
            mappings.stream().map(DataModelColumnMapping::getTargetColumn).toList()
        );
    }

    @Transactional
    public DwdModelCompileDTO compileArtifacts(UUID id) {
        DataModel model = loadModel(id);
        DwdModelValidationDTO validation = validate(id);
        if (!validation.ok()) {
            throw new BizException(40051, "DWD 模型静态校验失败: " + String.join("; ", validation.errors()));
        }

        CatalogAsset source = loadCatalogAsset(model.getTenantId(), model.getSourceFqn());
        List<DataModelColumnMapping> mappings = mappingRepo.findByModelIdOrderBySortNoAsc(model.getId());
        if (mappings.isEmpty()) {
            throw new BizException(40041, "DWD 模型字段映射不能为空");
        }

        String dbtModelName = normalizeName(model.getDbtModelName(), model.getTargetFqn());
        String sourceSchema = sourceSchemaName(source.fqn());
        String sourceTable = sourceTableName(source.fqn());
        Path dbtRoot = dbtProjectDir();
        String sourcePath = "models/generated/sources.yml";
        String sqlPath = "models/intermediate/" + dbtModelName + ".sql";
        String schemaPath = "models/intermediate/" + dbtModelName + ".yml";

        writeFile(dbtRoot.resolve(sourcePath), generateSourceYaml(source, sourceSchema, sourceTable));
        writeFile(dbtRoot.resolve(sqlPath), generateModelSql(model, mappings, sourceSchema, sourceTable));
        writeFile(dbtRoot.resolve(schemaPath), generateSchemaYaml(model, mappings, dbtModelName));

        applyExecutionDefaults(model);
        Map<String, Object> operatorGraph = operatorGraphDefinition(model, mappings, validation.outputColumns());
        model.setOperatorGraph(JsonUtil.toJson(operatorGraph));
        model.setOperatorGraphVersion(1);
        model.setCostPolicy(defaultCostPolicyJson());

        UUID dagId = ensureDwdDag(model, sqlPath, schemaPath, sourcePath, validation.outputColumns(), operatorGraph);

        model.setDbtModelName(dbtModelName);
        model.setCompiledSql(validation.compiledSql());
        model.setArtifactPath(sqlPath);
        model.setOrchestrationDagId(dagId);
        model.setStatus("VALIDATED");
        model.setUpdatedAt(Instant.now());
        modelRepo.save(model);

        return new DwdModelCompileDTO(
            model.getId(),
            dbtModelName,
            model.getMaterialization(),
            sqlPath,
            schemaPath,
            sourcePath,
            dagId,
            model.getDagsterJob(),
            model.getPipelineMode(),
            model.getOperatorGraphVersion(),
            model.getOperatorGraph(),
            model.getResourceGroup(),
            model.getComputeProfile(),
            model.getEngine(),
            model.getCostPolicy(),
            validation.compiledSql(),
            validation.dependencies(),
            validation.outputColumns()
        );
    }

    @Transactional
    public DwdModelRunDTO run(UUID id, DwdModelRunRequest request) {
        DataModel model = loadModel(id);
        if (!"VALIDATED".equalsIgnoreCase(model.getStatus())) {
            throw new BizException(40055, "DWD 模型必须先完成 compile/validate 后才能运行");
        }
        if (!StringUtils.hasText(model.getDbtModelName())) {
            throw new BizException(40056, "DWD 模型缺少 dbtModelName");
        }
        if (!StringUtils.hasText(model.getArtifactPath())) {
            throw new BizException(40057, "DWD 模型缺少 dbt 产物路径，请先重新 compile");
        }
        assignCurrentOwnerIfMissing(model);

        Instant now = Instant.now();
        String triggerType = normalizeTriggerType(request == null ? null : request.triggerType());
        DataModelRun run = new DataModelRun();
        run.setTenantId(model.getTenantId());
        run.setModelId(model.getId());
        run.setStatus("QUEUED");
        run.setTriggerType(triggerType);
        run.setSourceIntegrationRunId(request == null ? null : request.sourceIntegrationRunId());
        run.setOrchestrationDagId(model.getOrchestrationDagId());
        run.setResourceGroup(model.getResourceGroup());
        run.setComputeProfile(model.getComputeProfile());
        run.setArtifactsPath(model.getArtifactPath());
        if ("BACKFILL".equals(triggerType)) {
            run.setQueueReason(backfillSummary(request));
        }
        run.setQueuedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runRepo.save(run);

        model.setLastRunId(run.getId());
        model.setUpdatedAt(now);
        modelRepo.save(model);

        try {
            DwdModelDagsterClient.LaunchResult launch = dagsterClient.launchDwdModelRun(
                dagsterJob(model),
                dwdRunConfig(model, run, request),
                dwdRunTags(model, run)
            );
            run.setDagsterRunId(launch.runId());
            run.setStatus(mapDagsterStatus(launch.status()));
            if (run.getStartedAt() == null && !"QUEUED".equals(run.getStatus())) {
                run.setStartedAt(Instant.now());
            }
        } catch (RuntimeException e) {
            run.setStatus("FAILED");
            run.setStartedAt(now);
            run.setFinishedAt(Instant.now());
            run.setErrorMsg(truncate(e.getMessage(), 2000));
        }
        applyTerminalArtifactsAndPublish(model, run, "QUEUED");
        run.setUpdatedAt(Instant.now());
        runRepo.save(run);
        return toRunDTO(run);
    }

    @Transactional(readOnly = true)
    public List<DwdModelRunDTO> runs(UUID modelId) {
        DataModel model = loadModel(modelId);
        return runRepo.findByModelIdAndTenantIdOrderByQueuedAtDesc(model.getId(), model.getTenantId())
            .stream()
            .map(this::toRunDTO)
            .toList();
    }

    @Transactional
    public DwdModelRunDTO getRun(UUID runId) {
        DataModelRun run = runRepo.findByIdAndTenantId(runId, requireTenant())
            .orElseThrow(() -> new BizException(40442, "DWD 模型运行不存在"));
        refreshRunStatus(run);
        return toRunDTO(run);
    }

    private void refreshRunStatus(DataModelRun run) {
        if (!StringUtils.hasText(run.getDagsterRunId()) || isTerminal(run.getStatus())) {
            return;
        }
        String previousStatus = run.getStatus();
        try {
            DwdModelDagsterClient.RunStatus status = dagsterClient.getRunStatus(run.getDagsterRunId());
            String mapped = mapDagsterStatus(status.status());
            run.setStatus(mapped);
            if (status.startedAt() != null) {
                run.setStartedAt(status.startedAt());
            }
            if (status.finishedAt() != null) {
                run.setFinishedAt(status.finishedAt());
            }
            if (isTerminal(mapped) && run.getFinishedAt() == null) {
                run.setFinishedAt(Instant.now());
            }
            applyTerminalArtifactsAndPublish(loadModel(run.getModelId()), run, previousStatus);
            run.setUpdatedAt(Instant.now());
            runRepo.save(run);
        } catch (RuntimeException e) {
            run.setQueueReason(truncate("Dagster 状态刷新失败: " + e.getMessage(), 256));
            run.setUpdatedAt(Instant.now());
            runRepo.save(run);
        }
    }

    private void applyTerminalArtifactsAndPublish(DataModel model, DataModelRun run, String previousStatus) {
        if (!isTerminal(run.getStatus())) {
            return;
        }
        DwdRunArtifactReader.DbtRunArtifacts artifacts = null;
        if (StringUtils.hasText(run.getDagsterRunId())) {
            artifacts = artifactReader.read(model.getDbtModelName());
            if (artifacts.found()) {
                if (StringUtils.hasText(artifacts.artifactsPath())) {
                    run.setArtifactsPath(artifacts.artifactsPath());
                }
                if (artifacts.rowsAffected() != null) {
                    run.setRowsWritten(artifacts.rowsAffected());
                    run.setRowsRead(artifacts.rowsAffected());
                }
                if (!"SUCCEEDED".equals(run.getStatus()) && StringUtils.hasText(artifacts.errorMessage())) {
                    run.setErrorMsg(truncate(artifacts.errorMessage(), 2000));
                }
            } else if (!"SUCCEEDED".equals(run.getStatus()) && StringUtils.hasText(artifacts.errorMessage())) {
                run.setErrorMsg(truncate(firstText(run.getErrorMsg(), artifacts.errorMessage()), 2000));
            }
        }
        if (!isTerminal(previousStatus)) {
            publishModelRunEvent(model, run, artifacts);
        }
    }

    private void publishModelRunEvent(DataModel model, DataModelRun run, DwdRunArtifactReader.DbtRunArtifacts artifacts) {
        String eventType = "SUCCEEDED".equals(run.getStatus())
            ? DomainEvents.MODELING_MODEL_LOADED
            : DomainEvents.MODELING_MODEL_FAILED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", model.getTenantId().toString());
        payload.put("modelId", model.getId().toString());
        payload.put("runId", run.getId().toString());
        payload.put("status", run.getStatus());
        payload.put("triggerType", run.getTriggerType());
        payload.put("sourceIntegrationRunId", run.getSourceIntegrationRunId() == null ? "" : run.getSourceIntegrationRunId().toString());
        payload.put("orchestrationDagId", run.getOrchestrationDagId() == null ? "" : run.getOrchestrationDagId().toString());
        payload.put("dagsterRunId", nullToEmpty(run.getDagsterRunId()));
        payload.put("dbtModelName", nullToEmpty(model.getDbtModelName()));
        payload.put("sourceFqn", model.getSourceFqn());
        payload.put("targetFqn", model.getTargetFqn());
        payload.put("ownerId", model.getOwnerId() == null ? "" : model.getOwnerId().toString());
        payload.put("ownerName", nullToEmpty(model.getOwnerName()));
        payload.put("resourceGroup", nullToEmpty(run.getResourceGroup()));
        payload.put("computeProfile", nullToEmpty(run.getComputeProfile()));
        payload.put("rowsRead", run.getRowsRead() == null ? 0L : run.getRowsRead());
        payload.put("rowsWritten", run.getRowsWritten() == null ? 0L : run.getRowsWritten());
        payload.put("artifactsPath", nullToEmpty(run.getArtifactsPath()));
        payload.put("errorMsg", nullToEmpty(run.getErrorMsg()));
        payload.put("fieldMapping", fieldMappingPayload(model.getId()));
        payload.put("qualityChecks", qualityChecksPayload(artifacts));
        outboxPublisher.publish(eventType, run.getId().toString(), payload);
    }

    private DwdModelRunDTO toRunDTO(DataModelRun run) {
        return new DwdModelRunDTO(
            run.getId(),
            run.getModelId(),
            run.getStatus(),
            run.getTriggerType(),
            run.getSourceIntegrationRunId(),
            run.getOrchestrationDagId(),
            run.getDagsterRunId(),
            run.getEngineRunId(),
            run.getTrinoQueryId(),
            run.getResourceGroup(),
            run.getComputeProfile(),
            run.getQueuedAt(),
            run.getStartedAt(),
            run.getFinishedAt(),
            run.getErrorMsg(),
            run.getRowsRead(),
            run.getRowsWritten(),
            run.getArtifactsPath(),
            run.getEstimatedScanBytes(),
            run.getActualScanBytes(),
            run.getCostEstimate(),
            run.getQueueReason(),
            run.getRetryCount(),
            run.getCreatedAt(),
            run.getUpdatedAt()
        );
    }

    private Map<String, Object> dwdRunConfig(DataModel model, DataModelRun run, DwdModelRunRequest request) {
        return Map.of(
            "ops", Map.of(
                "run_dwd_model", Map.of(
                    "config", dwdRunConfigPayload(model, run, request)
                )
            )
        );
    }

    private Map<String, Object> dwdRunConfigPayload(DataModel model, DataModelRun run, DwdModelRunRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model_name", model.getDbtModelName());
        config.put("model_id", model.getId().toString());
        config.put("run_id", run.getId().toString());
        config.put("tenant_id", model.getTenantId().toString());
        config.put("trigger_type", run.getTriggerType());
        config.put("source_fqn", model.getSourceFqn());
        config.put("target_fqn", model.getTargetFqn());
        config.put("artifact_path", nullToEmpty(model.getArtifactPath()));
        config.put("resource_group", nullToEmpty(model.getResourceGroup()));
        config.put("compute_profile", nullToEmpty(model.getComputeProfile()));
        config.put("backfill", backfillConfig(request));
        return config;
    }

    private List<Map<String, String>> dwdRunTags(DataModel model, DataModelRun run) {
        return List.of(
            Map.of("key", "onelake.model_id", "value", model.getId().toString()),
            Map.of("key", "onelake.model_run_id", "value", run.getId().toString()),
            Map.of("key", "onelake.tenant_id", "value", model.getTenantId().toString()),
            Map.of("key", "onelake.trigger_type", "value", run.getTriggerType()),
            Map.of("key", "onelake.dbt_model", "value", model.getDbtModelName())
        );
    }

    private List<Map<String, Object>> fieldMappingPayload(UUID modelId) {
        return mappingRepo.findByModelIdOrderBySortNoAsc(modelId)
            .stream()
            .map(mapping -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", mapping.getSourceColumn());
                item.put("target", mapping.getTargetColumn());
                item.put("sourceType", nullToEmpty(mapping.getSourceType()));
                item.put("targetType", nullToEmpty(mapping.getTargetType()));
                item.put("expression", nullToEmpty(mapping.getExpression()));
                item.put("primaryKey", Boolean.TRUE.equals(mapping.getPrimaryKey()));
                item.put("classification", nullToEmpty(mapping.getClassification()));
                item.put("piiType", nullToEmpty(mapping.getPiiType()));
                item.put("suggestLevel", nullToEmpty(mapping.getSuggestLevel()));
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> qualityChecksPayload(DwdRunArtifactReader.DbtRunArtifacts artifacts) {
        if (artifacts == null || artifacts.checks() == null || artifacts.checks().isEmpty()) {
            return List.of();
        }
        return artifacts.checks().stream()
            .map(check -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("uniqueId", nullToEmpty(check.uniqueId()));
                item.put("name", nullToEmpty(check.name()));
                item.put("status", nullToEmpty(check.status()));
                item.put("failures", check.failures() == null ? 0L : check.failures());
                item.put("message", nullToEmpty(check.message()));
                return item;
            })
            .toList();
    }

    private String normalizeTriggerType(String triggerType) {
        if (!StringUtils.hasText(triggerType)) {
            return "MANUAL";
        }
        String normalized = triggerType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("MANUAL", "ODS_EVENT", "BACKFILL", "RETRY").contains(normalized)) {
            throw new BizException(40058, "不支持的 DWD 运行触发类型: " + triggerType);
        }
        return normalized;
    }

    private Map<String, Object> backfillConfig(DwdModelRunRequest request) {
        if (request == null || !"BACKFILL".equalsIgnoreCase(nullToEmpty(request.triggerType()))) {
            return Map.of(
                "enabled", false,
                "fullRefresh", false,
                "partitionStart", "",
                "partitionEnd", "",
                "sourceIntegrationRunId", ""
            );
        }
        return Map.of(
            "enabled", true,
            "fullRefresh", Boolean.TRUE.equals(request.fullRefresh()),
            "partitionStart", nullToEmpty(request.partitionStart()),
            "partitionEnd", nullToEmpty(request.partitionEnd()),
            "sourceIntegrationRunId", request.sourceIntegrationRunId() == null ? "" : request.sourceIntegrationRunId().toString()
        );
    }

    private String backfillSummary(DwdModelRunRequest request) {
        if (request == null) {
            return "BACKFILL";
        }
        List<String> parts = new ArrayList<>();
        if (Boolean.TRUE.equals(request.fullRefresh())) {
            parts.add("fullRefresh=true");
        }
        if (StringUtils.hasText(request.partitionStart()) || StringUtils.hasText(request.partitionEnd())) {
            parts.add("range=" + nullToEmpty(request.partitionStart()) + ".." + nullToEmpty(request.partitionEnd()));
        }
        if (request.sourceIntegrationRunId() != null) {
            parts.add("sourceIntegrationRunId=" + request.sourceIntegrationRunId());
        }
        return parts.isEmpty() ? "BACKFILL" : "BACKFILL " + String.join(" ", parts);
    }

    private String dagsterJob(DataModel model) {
        return StringUtils.hasText(model.getDagsterJob()) ? model.getDagsterJob() : "onelake_dbt_model_run";
    }

    private String mapDagsterStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "RUNNING";
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCEEDED" -> "SUCCEEDED";
            case "FAILURE", "FAILED" -> "FAILED";
            case "CANCELED", "CANCELLED" -> "CANCELLED";
            case "QUEUED", "NOT_STARTED" -> "QUEUED";
            default -> "RUNNING";
        };
    }

    private boolean isTerminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        return tenantId;
    }

    private DataModel loadModel(UUID id) {
        return modelRepo.findByIdAndTenantId(id, requireTenant())
            .orElseThrow(() -> new BizException(40440, "DWD 模型不存在"));
    }

    private CatalogAsset loadCatalogAsset(UUID tenantId, String fqn) {
        try {
            return jdbc.queryForObject("""
                SELECT om_fqn, layer, domain, columns
                FROM catalog.asset
                WHERE tenant_id = ? AND om_fqn = ?
                """, (rs, rowNum) -> new CatalogAsset(
                    rs.getString("om_fqn"),
                    rs.getString("layer"),
                    rs.getString("domain"),
                    parseColumns(rs.getString("columns"))
                ), tenantId, fqn);
        } catch (EmptyResultDataAccessException e) {
            throw new BizException(40441, "上游 ODS 资产不存在: " + fqn);
        }
    }

    private void requireOdsSource(CatalogAsset source) {
        String layer = source.layer();
        if (!"ODS".equalsIgnoreCase(layer) && !source.fqn().toLowerCase(Locale.ROOT).startsWith("ods.")) {
            throw new BizException(40043, "DWD 只能从 ODS 资产派生");
        }
        if (source.columns().isEmpty()) {
            throw new BizException(40044, "上游 ODS 资产字段为空，请先刷新 Catalog 字段");
        }
    }

    private String normalizeName(String name, String targetFqn) {
        if (StringUtils.hasText(name)) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
        if (StringUtils.hasText(targetFqn) && targetFqn.contains(".")) {
            return targetFqn.substring(targetFqn.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        throw new BizException(40045, "DWD 模型名称不能为空");
    }

    private String normalizeTargetFqn(String targetFqn, String name) {
        if (StringUtils.hasText(targetFqn)) {
            return targetFqn.trim().toLowerCase(Locale.ROOT);
        }
        return "dwd." + name;
    }

    private void validateTargetName(String name, String targetFqn) {
        if (!targetFqn.startsWith("dwd.")) {
            throw new BizException(40046, "DWD 目标表必须位于 dwd 分层");
        }
        if (!TABLE_NAME.matcher(name).matches()) {
            throw new BizException(40047, "表名必须符合 layer_domain_business_granularity，例如 dwd_trade_order_df");
        }
        if (!name.startsWith("dwd_")) {
            throw new BizException(40048, "DWD 表名必须以 dwd_ 开头");
        }
    }

    private String normalizeMaterialization(String materialization) {
        if (!StringUtils.hasText(materialization)) {
            return "TABLE";
        }
        String normalized = materialization.trim().toUpperCase(Locale.ROOT);
        if (!List.of("VIEW", "TABLE", "INCREMENTAL").contains(normalized)) {
            throw new BizException(40049, "不支持的模型物化方式: " + materialization);
        }
        return normalized;
    }

    private List<ColumnDraft> normalizeMappings(
        List<DwdModelDraftRequest.ColumnMappingRequest> requested,
        List<CatalogColumn> sourceColumns
    ) {
        Map<String, CatalogColumn> sourceByName = new LinkedHashMap<>();
        for (CatalogColumn column : sourceColumns) {
            sourceByName.put(column.name(), column);
        }
        List<ColumnDraft> drafts = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            int i = 0;
            for (CatalogColumn source : sourceColumns) {
                drafts.add(new ColumnDraft(
                    source.name(),
                    source.name(),
                    source.type(),
                    source.type(),
                    null,
                    isLikelyPrimaryKey(source.name()),
                    source.classification(),
                    source.piiType(),
                    source.suggestLevel(),
                    i++
                ));
            }
            return drafts;
        }
        int i = 0;
        for (DwdModelDraftRequest.ColumnMappingRequest item : requested) {
            String sourceName = text(item.source(), "source");
            String targetName = text(item.target(), "target");
            CatalogColumn source = sourceByName.get(sourceName);
            if (source == null) {
                throw new BizException(40050, "字段映射引用了不存在的源字段: " + sourceName);
            }
            drafts.add(new ColumnDraft(
                sourceName,
                targetName,
                StringUtils.hasText(item.sourceType()) ? item.sourceType() : source.type(),
                StringUtils.hasText(item.targetType()) ? item.targetType() : source.type(),
                blankToNull(item.expression()),
                Boolean.TRUE.equals(item.primaryKey()),
                firstText(item.classification(), source.classification()),
                firstText(item.piiType(), source.piiType()),
                firstText(item.suggestLevel(), source.suggestLevel()),
                i++
            ));
        }
        return drafts;
    }

    private void saveMappings(UUID modelId, List<ColumnDraft> mappings) {
        for (ColumnDraft item : mappings) {
            DataModelColumnMapping mapping = new DataModelColumnMapping();
            mapping.setModelId(modelId);
            mapping.setSourceColumn(item.source());
            mapping.setTargetColumn(item.target());
            mapping.setSourceType(item.sourceType());
            mapping.setTargetType(item.targetType());
            mapping.setExpression(item.expression());
            mapping.setPrimaryKey(item.primaryKey());
            mapping.setClassification(item.classification());
            mapping.setPiiType(item.piiType());
            mapping.setSuggestLevel(item.suggestLevel());
            mapping.setSortNo(item.sortNo());
            mappingRepo.save(mapping);
        }
    }

    private DataModelDTO toDTO(DataModel model) {
        List<DataModelSource> sources = sourceRepo.findByModelIdOrderBySortNoAsc(model.getId());
        List<DataModelColumnMapping> mappings = mappingRepo.findByModelIdOrderBySortNoAsc(model.getId());
        return new DataModelDTO(
            model.getId(),
            model.getName(),
            model.getLayer(),
            model.getDomain(),
            model.getSourceFqn(),
            model.getTargetFqn(),
            model.getStatus(),
            model.getMaterialization(),
            model.getUniqueKey(),
            model.getIncrementalColumn(),
            model.getPartitionExpr(),
            model.getSqlText(),
            model.getCompiledSql(),
            model.getDbtModelName(),
            model.getOrchestrationDagId(),
            model.getDagsterJob(),
            model.getArtifactPath(),
            model.getLastRunId(),
            model.getPipelineMode(),
            model.getOperatorGraphVersion(),
            model.getOperatorGraph(),
            model.getResourceGroup(),
            model.getComputeProfile(),
            model.getEngine(),
            model.getCostPolicy(),
            model.getOwnerId(),
            model.getOwnerName(),
            model.getCreatedAt(),
            model.getUpdatedAt(),
            sources.stream().map(s -> new DataModelDTO.SourceDTO(
                s.getId(), s.getSourceFqn(), s.getSourceType(), s.getSortNo()
            )).toList(),
            mappings.stream().map(m -> new DataModelDTO.ColumnMappingDTO(
                m.getId(),
                m.getSourceColumn(),
                m.getTargetColumn(),
                m.getSourceType(),
                m.getTargetType(),
                m.getExpression(),
                m.getPrimaryKey(),
                m.getClassification(),
                m.getPiiType(),
                m.getSuggestLevel(),
                m.getSortNo()
            )).toList()
        );
    }

    private List<CatalogColumn> parseColumns(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            JsonNode node = JsonUtil.parse(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<CatalogColumn> columns = new ArrayList<>();
            for (JsonNode item : node) {
                String name = item.path("name").asText("");
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                columns.add(new CatalogColumn(
                    name,
                    item.path("type").asText("STRING"),
                    item.path("classification").asText(null),
                    item.path("piiType").asText(null),
                    item.path("suggestLevel").asText(null)
                ));
            }
            return columns;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String compileSql(String sourceFqn, List<ColumnDraft> mappings) {
        StringBuilder sql = new StringBuilder("select\n");
        for (int i = 0; i < mappings.size(); i++) {
            ColumnDraft mapping = mappings.get(i);
            String expression = StringUtils.hasText(mapping.expression())
                ? mapping.expression()
                : quote(mapping.source());
            sql.append("  ").append(expression).append(" as ").append(quote(mapping.target()));
            if (i < mappings.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        sql.append("from ").append(sourceFqn);
        return sql.toString();
    }

    private String generateModelSql(
        DataModel model,
        List<DataModelColumnMapping> mappings,
        String sourceSchema,
        String sourceTable
    ) {
        StringBuilder sql = new StringBuilder();
        sql.append("{{ config(materialized='")
            .append(dbtMaterialization(model.getMaterialization()))
            .append("', schema='dwd'");
        if ("INCREMENTAL".equalsIgnoreCase(model.getMaterialization())
            && StringUtils.hasText(model.getUniqueKey())) {
            sql.append(", unique_key='").append(model.getUniqueKey().replace("'", "''")).append("'");
            sql.append(", incremental_strategy='merge'");
        }
        sql.append(") }}\n\n");
        sql.append("select\n");
        for (int i = 0; i < mappings.size(); i++) {
            DataModelColumnMapping mapping = mappings.get(i);
            String expression = StringUtils.hasText(mapping.getExpression())
                ? mapping.getExpression()
                : quote(mapping.getSourceColumn());
            sql.append("  ").append(expression).append(" as ").append(quote(mapping.getTargetColumn()));
            if (i < mappings.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        sql.append("from {{ source('")
            .append(sourceSchema)
            .append("', '")
            .append(sourceTable)
            .append("') }}\n");
        if ("INCREMENTAL".equalsIgnoreCase(model.getMaterialization())
            && StringUtils.hasText(model.getIncrementalColumn())) {
            String watermark = quote(model.getIncrementalColumn());
            sql.append("{% if is_incremental() %}\n")
                .append("where ").append(watermark)
                .append(" > (select coalesce(max(").append(watermark)
                .append("), timestamp '1970-01-01 00:00:00') from {{ this }})\n")
                .append("{% endif %}\n");
        }
        return sql.toString();
    }

    private String generateSourceYaml(CatalogAsset source, String sourceSchema, String sourceTable) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 2\n");
        yaml.append("sources:\n");
        yaml.append("  - name: ").append(sourceSchema).append("\n");
        yaml.append("    schema: ").append(sourceSchema).append("\n");
        yaml.append("    description: ").append(yamlQuote("OneLake generated source for " + source.fqn())).append("\n");
        yaml.append("    tables:\n");
        yaml.append("      - name: ").append(sourceTable).append("\n");
        yaml.append("        description: ").append(yamlQuote("Generated from Catalog asset " + source.fqn())).append("\n");
        yaml.append("        columns:\n");
        for (CatalogColumn column : source.columns()) {
            yaml.append("          - name: ").append(column.name()).append("\n");
            yaml.append("            description: ")
                .append(yamlQuote("Source column " + column.name() + " (" + column.type() + ")"))
                .append("\n");
        }
        return yaml.toString();
    }

    private String generateSchemaYaml(
        DataModel model,
        List<DataModelColumnMapping> mappings,
        String dbtModelName
    ) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 2\n");
        yaml.append("models:\n");
        yaml.append("  - name: ").append(dbtModelName).append("\n");
        yaml.append("    description: ").append(yamlQuote("OneLake generated DWD model from " + model.getSourceFqn())).append("\n");
        yaml.append("    columns:\n");
        for (DataModelColumnMapping mapping : mappings) {
            yaml.append("      - name: ").append(mapping.getTargetColumn()).append("\n");
            yaml.append("        description: ")
                .append(yamlQuote("Mapped from " + mapping.getSourceColumn()))
                .append("\n");
            if (Boolean.TRUE.equals(mapping.getPrimaryKey())) {
                yaml.append("        tests:\n");
                yaml.append("          - not_null\n");
                yaml.append("          - unique\n");
            }
        }
        return yaml.toString();
    }

    private UUID ensureDwdDag(
        DataModel model,
        String sqlPath,
        String schemaPath,
        String sourcePath,
        List<String> outputColumns,
        Map<String, Object> operatorGraph
    ) {
        String dagsterJob = StringUtils.hasText(model.getDagsterJob()) ? model.getDagsterJob() : "onelake_dbt_model_run";
        String dagName = "DWD " + model.getName();
        String definition = JsonUtil.toJson(dagDefinition(model, sqlPath, schemaPath, sourcePath, outputColumns, operatorGraph));
        if (model.getOrchestrationDagId() != null) {
            int updated = jdbc.update("""
                UPDATE orchestration.dag
                SET name = ?,
                    dagster_job = ?,
                    definition = ?::jsonb,
                    enabled = false,
                    version = COALESCE(version, 1) + 1
                WHERE id = ? AND tenant_id = ?
                """, dagName, dagsterJob, definition, model.getOrchestrationDagId(), model.getTenantId());
            if (updated > 0) {
                return model.getOrchestrationDagId();
            }
        }
        UUID dagId = jdbc.queryForObject("""
            INSERT INTO orchestration.dag
                (tenant_id, name, dagster_job, definition, schedule_cron, enabled, version, created_at)
            VALUES (?, ?, ?, ?::jsonb, NULL, false, 1, now())
            RETURNING id
            """, UUID.class, model.getTenantId(), dagName, dagsterJob, definition);
        if (dagId == null) {
            throw new BizException(50051, "DWD 编排 DAG 草稿创建失败");
        }
        return dagId;
    }

    private Map<String, Object> dagDefinition(
        DataModel model,
        String sqlPath,
        String schemaPath,
        String sourcePath,
        List<String> outputColumns,
        Map<String, Object> operatorGraph
    ) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("kind", "DWD_MODEL_DAG");
        definition.put("version", 1);
        definition.put("modelId", model.getId().toString());
        definition.put("sourceFqn", model.getSourceFqn());
        definition.put("targetFqn", model.getTargetFqn());
        definition.put("dbtModelName", model.getDbtModelName());
        definition.put("engine", model.getEngine());
        definition.put("resourceGroup", model.getResourceGroup());
        definition.put("computeProfile", model.getComputeProfile());
        definition.put("costPolicy", JsonUtil.parse(model.getCostPolicy()));
        definition.put("materialization", model.getMaterialization());
        definition.put("outputColumns", outputColumns);
        definition.put("operatorGraph", operatorGraph);
        definition.put("nodes", operatorGraph.get("nodes"));
        definition.put("edges", operatorGraph.get("edges"));
        return definition;
    }

    private Map<String, Object> operatorGraphDefinition(
        DataModel model,
        List<DataModelColumnMapping> mappings,
        List<String> outputColumns
    ) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node(
            "input_ods", "INPUT", model.getSourceFqn(),
            Map.of("assetFqn", model.getSourceFqn(), "layer", "ODS")
        ));
        nodes.add(node(
            "transform_mapping", "TRANSFORM", "字段选择与标准化",
            Map.of(
                "sourceFqn", model.getSourceFqn(),
                "targetFqn", model.getTargetFqn(),
                "mappings", mappings.stream().map(m -> Map.of(
                    "source", m.getSourceColumn(),
                    "target", m.getTargetColumn(),
                    "expression", StringUtils.hasText(m.getExpression()) ? m.getExpression() : m.getSourceColumn()
                )).toList()
            )
        ));
        nodes.add(node(
            "govern_clean", "GOVERN", "脏数据过滤与标准治理",
            Map.of(
                "policies", List.of(
                    Map.of("type", "PRIMARY_KEY_NOT_NULL", "actionOnViolation", "FAIL"),
                    Map.of("type", "ENUM_STANDARDIZE", "actionOnViolation", "WARN")
                )
            )
        ));
        if (mappings.stream().anyMatch(m -> isSensitive(m.getClassification()) || isSensitive(m.getSuggestLevel()))) {
            nodes.add(node(
                "mask_sensitive", "MASK", "敏感字段透传检查",
                Map.of(
                    "actionOnViolation", "WARN",
                    "columns", mappings.stream()
                        .filter(m -> isSensitive(m.getClassification()) || isSensitive(m.getSuggestLevel()))
                        .map(DataModelColumnMapping::getTargetColumn)
                        .toList()
                )
            ));
        }
        nodes.add(node(
            "quality_gate", "QUALITY_GATE", "主键与非空门禁",
            Map.of("actionOnViolation", "FAIL", "tests", List.of("not_null", "unique"))
        ));
        nodes.add(node(
            "dbt_model", "DBT_MODEL", model.getDbtModelName(),
            Map.of(
                "dbtModelName", model.getDbtModelName(),
                "engine", model.getEngine(),
                "resourceGroup", model.getResourceGroup(),
                "computeProfile", model.getComputeProfile()
            )
        ));
        nodes.add(node(
            "output_dwd", "OUTPUT", model.getTargetFqn(),
            Map.of("assetFqn", model.getTargetFqn(), "layer", "DWD", "columns", outputColumns)
        ));

        List<Map<String, String>> edges = new ArrayList<>();
        edges.add(Map.of("source", "input_ods", "target", "transform_mapping"));
        edges.add(Map.of("source", "transform_mapping", "target", "govern_clean"));
        String previous = "govern_clean";
        if (nodes.stream().anyMatch(n -> "mask_sensitive".equals(n.get("id")))) {
            edges.add(Map.of("source", previous, "target", "mask_sensitive"));
            previous = "mask_sensitive";
        }
        edges.add(Map.of("source", previous, "target", "quality_gate"));
        edges.add(Map.of("source", "quality_gate", "target", "dbt_model"));
        edges.add(Map.of("source", "dbt_model", "target", "output_dwd"));

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("version", 1);
        graph.put("pipelineMode", model.getPipelineMode());
        graph.put("engine", model.getEngine());
        graph.put("resourceGroup", model.getResourceGroup());
        graph.put("computeProfile", model.getComputeProfile());
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    private Map<String, Object> node(String id, String nodeType, String name, Map<String, Object> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", nodeType);
        node.put("nodeType", nodeType);
        node.put("name", name);
        node.put("config", config);
        node.put("resourceProfile", Map.of("resourceGroup", "default", "computeProfile", "trino-small"));
        return node;
    }

    private void applyExecutionDefaults(DataModel model) {
        if (!StringUtils.hasText(model.getPipelineMode())) {
            model.setPipelineMode("SYSTEM_GENERATED");
        }
        if (model.getOperatorGraphVersion() == null) {
            model.setOperatorGraphVersion(1);
        }
        if (!StringUtils.hasText(model.getResourceGroup())) {
            model.setResourceGroup("default");
        }
        if (!StringUtils.hasText(model.getComputeProfile())) {
            model.setComputeProfile("trino-small");
        }
        if (!StringUtils.hasText(model.getEngine())) {
            model.setEngine("TRINO_DBT");
        }
        if (!StringUtils.hasText(model.getCostPolicy()) || "{}".equals(model.getCostPolicy())) {
            model.setCostPolicy(defaultCostPolicyJson());
        }
    }

    private String defaultCostPolicyJson() {
        return JsonUtil.toJson(Map.of(
            "maxScanBytes", 1_099_511_627_776L,
            "timeoutMinutes", 30,
            "retryCount", 0,
            "actionOnLargeScan", "CONFIRM"
        ));
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(50050, "DWD dbt 产物写入失败: " + e.getMessage());
        }
    }

    private Path dbtProjectDir() {
        String configured = System.getProperty("onelake.dbt.projectDir");
        if (!StringUtils.hasText(configured)) {
            configured = System.getenv("ONELAKE_DBT_PROJECT_DIR");
        }
        if (!StringUtils.hasText(configured)) {
            configured = "../dbt";
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private String dbtMaterialization(String materialization) {
        String normalized = normalizeMaterialization(materialization);
        return switch (normalized) {
            case "VIEW" -> "view";
            case "INCREMENTAL" -> "incremental";
            default -> "table";
        };
    }

    private String sourceSchemaName(String fqn) {
        int dot = fqn.indexOf('.');
        if (dot <= 0) {
            throw new BizException(40052, "ODS source_fqn 必须包含 schema.table: " + fqn);
        }
        return fqn.substring(0, dot);
    }

    private String sourceTableName(String fqn) {
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1) {
            throw new BizException(40052, "ODS source_fqn 必须包含 schema.table: " + fqn);
        }
        return fqn.substring(dot + 1);
    }

    private String yamlQuote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    private ColumnDraft toDraft(DataModelColumnMapping mapping) {
        return new ColumnDraft(
            mapping.getSourceColumn(),
            mapping.getTargetColumn(),
            mapping.getSourceType(),
            mapping.getTargetType(),
            mapping.getExpression(),
            Boolean.TRUE.equals(mapping.getPrimaryKey()),
            mapping.getClassification(),
            mapping.getPiiType(),
            mapping.getSuggestLevel(),
            mapping.getSortNo() == null ? 0 : mapping.getSortNo()
        );
    }

    private String quote(String name) {
        if (name == null) {
            return "";
        }
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private boolean isSensitive(String level) {
        return "L3".equalsIgnoreCase(level) || "L4".equalsIgnoreCase(level);
    }

    private boolean isTransformExpression(String expression) {
        if (!StringUtils.hasText(expression)) {
            return false;
        }
        String lower = expression.toLowerCase(Locale.ROOT);
        return lower.contains("mask") || lower.contains("hash") || lower.contains("sha") || lower.contains("encrypt");
    }

    private boolean isLikelyPrimaryKey(String name) {
        return "id".equalsIgnoreCase(name) || name.toLowerCase(Locale.ROOT).endsWith("_id");
    }

    private String text(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(40040, field + " 不能为空");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : blankToNull(second);
    }

    private void assignCurrentOwnerIfMissing(DataModel model) {
        if (model.getOwnerId() == null) {
            model.setOwnerId(TenantContext.getUserId());
        }
        if (!StringUtils.hasText(model.getOwnerName())) {
            model.setOwnerName(TenantContext.getUsername());
        }
    }

    private record CatalogAsset(String fqn, String layer, String domain, List<CatalogColumn> columns) {}

    private record CatalogColumn(
        String name,
        String type,
        String classification,
        String piiType,
        String suggestLevel
    ) {}

    private record ColumnDraft(
        String source,
        String target,
        String sourceType,
        String targetType,
        String expression,
        boolean primaryKey,
        String classification,
        String piiType,
        String suggestLevel,
        int sortNo
    ) {}
}

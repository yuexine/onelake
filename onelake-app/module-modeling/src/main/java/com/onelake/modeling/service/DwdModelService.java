package com.onelake.modeling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.sql.ReadOnlySqlValidator;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.modeling.domain.entity.DataModel;
import com.onelake.modeling.domain.entity.DataModelColumnMapping;
import com.onelake.modeling.domain.entity.DataModelSource;
import com.onelake.modeling.dto.DataModelDTO;
import com.onelake.modeling.dto.DwdModelCompileDTO;
import com.onelake.modeling.dto.DwdModelDraftRequest;
import com.onelake.modeling.dto.DwdModelValidationDTO;
import com.onelake.modeling.repository.DataModelColumnMappingRepository;
import com.onelake.modeling.repository.DataModelRepository;
import com.onelake.modeling.repository.DataModelSourceRepository;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.statement.Statement;
import org.springframework.dao.DataAccessException;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DwdModelService {

    private static final Pattern TABLE_NAME =
        Pattern.compile("^(ods|dwd|dws|ads)_([a-z]+)_([a-z_]+)_(df|di|dms|d|w|m|y|app)$");
    private static final String OP_INPUT_ODS_TABLE = "input.ods_table";
    private static final String OP_TRANSFORM_RENAME_COLUMNS = "transform.rename_columns";
    private static final String OP_TRANSFORM_SPARK_SQL = "transform.spark_sql";
    private static final String OP_GOVERN_DROP_REQUIRED_MISSING = "govern.drop_required_missing";
    private static final String OP_MASK_PARTIAL = "mask.partial";
    private static final String OP_GATE_NOT_NULL = "gate.not_null";
    private static final String OP_OUTPUT_ICEBERG_TABLE = "output.iceberg_table";
    private static final String OP_OUTPUT_VIEW = "output.view";
    private static final String OP_OUTPUT_INCREMENTAL_MERGE = "output.incremental_merge";
    private static final Pattern FRESHNESS_DELAY = Pattern.compile("^([0-9]+)\\s*([a-zA-Z]+)$");
    private static final String CUSTOM_SQL_TEMPLATE_PLACEHOLDER = "__ONELAKE_TEMPLATE__";

    private final DataModelRepository modelRepo;
    private final DataModelSourceRepository sourceRepo;
    private final DataModelColumnMappingRepository mappingRepo;
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

        List<ColumnDraft> mappings = normalizeMappings(tenantId, request.columnMappings(), source.columns());
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
        model.setDagsterJob("onelake_pipeline_run");
        model.setPipelineMode("SYSTEM_GENERATED");
        model.setOperatorGraphVersion(1);
        model.setResourceGroup("spark-default");
        model.setComputeProfile("spark-small");
        model.setEngine("SPARK");
        model.setCostPolicy(defaultCostPolicyJson());
        applyDraftExecutionRequest(model, request);
        assignCurrentOwnerIfMissing(model);
        String compiledSql = compileSql(source.fqn(), mappings, operatorGraphMap(model.getOperatorGraph()));
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

        saveMappings(model, mappings);
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
        if ("PUBLISHED".equalsIgnoreCase(model.getStatus())) {
            throw new BizException(40042, "已发布模型不可直接编辑，请新建版本");
        }
        if (!"DRAFT".equalsIgnoreCase(model.getStatus()) && !"VALIDATED".equalsIgnoreCase(model.getStatus())) {
            throw new BizException(40042, "只有草稿或已校验模型可以编辑");
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

        List<ColumnDraft> mappings = normalizeMappings(tenantId, request.columnMappings(), source.columns());
        if (mappings.isEmpty()) {
            throw new BizException(40041, "DWD 模型字段映射不能为空");
        }

        model.setName(name);
        model.setDomain(StringUtils.hasText(request.domain()) ? request.domain().trim() : source.domain());
        model.setSourceFqn(source.fqn());
        model.setTargetFqn(targetFqn);
        model.setStatus("DRAFT");
        model.setMaterialization(normalizeMaterialization(request.materialization()));
        model.setUniqueKey(blankToNull(request.uniqueKey()));
        model.setIncrementalColumn(blankToNull(request.incrementalColumn()));
        model.setPartitionExpr(blankToNull(request.partitionExpr()));
        model.setDbtModelName(name);
        applyExecutionDefaults(model);
        applyDraftExecutionRequest(model, request);
        String compiledSql = compileSql(source.fqn(), mappings, operatorGraphMap(model.getOperatorGraph()));
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
        saveMappings(model, mappings);
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

        String compiledSql = compileSql(
            model.getSourceFqn(),
            mappings.stream().map(this::toDraft).toList(),
            operatorGraphMap(model.getOperatorGraph())
        );
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

        applyExecutionDefaults(model);
        Map<String, ResolvedOperator> operators = resolveDefaultOperators(model, mappings);

        String dbtModelName = normalizeName(model.getDbtModelName(), model.getTargetFqn());
        String sourceSchema = sourceSchemaName(source.fqn());
        String sourceTable = sourceTableName(source.fqn());
        Path dbtRoot = dbtProjectDir();
        String sourcePath = "models/generated/sources.yml";
        String sqlPath = "models/intermediate/" + dbtModelName + ".sql";
        String schemaPath = "models/intermediate/" + dbtModelName + ".yml";
        Map<String, Object> storedGraph = operatorGraphMap(model.getOperatorGraph());
        Map<String, Object> operatorGraph = operatorGraphDefinition(model, mappings, validation.outputColumns(), operators);
        operatorGraph = mergeStoredGovernanceNodes(operatorGraph, storedGraph);
        operatorGraph = mergeStoredQualityGateNodes(operatorGraph, storedGraph);

        writeFile(dbtRoot.resolve(sourcePath), generateSourceYaml(model, source, operatorGraph));
        writeFile(dbtRoot.resolve(sqlPath), generateModelSql(model, mappings, sourceSchema, sourceTable, operatorGraph));
        writeFile(dbtRoot.resolve(schemaPath), generateSchemaYaml(model, mappings, dbtModelName, operatorGraph));

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
    public DataModelDTO publish(UUID id, String comment) {
        DataModel model = loadModel(id);
        if (!"VALIDATED".equalsIgnoreCase(model.getStatus()) && !"PUBLISHED".equalsIgnoreCase(model.getStatus())) {
            throw new BizException(40062, "DWD 模型必须先完成编译校验后才能发布");
        }
        if (!StringUtils.hasText(model.getArtifactPath()) || model.getOrchestrationDagId() == null) {
            throw new BizException(40063, "DWD 模型缺少编译产物或编排 DAG，请先重新编译");
        }
        assignCurrentOwnerIfMissing(model);
        model.setStatus("PUBLISHED");
        model.setUpdatedAt(Instant.now());
        modelRepo.save(model);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", model.getTenantId().toString());
        payload.put("modelId", model.getId().toString());
        payload.put("sourceFqn", model.getSourceFqn());
        payload.put("targetFqn", model.getTargetFqn());
        payload.put("dbtModelName", nullToEmpty(model.getDbtModelName()));
        payload.put("orchestrationDagId", model.getOrchestrationDagId().toString());
        payload.put("resourceGroup", nullToEmpty(model.getResourceGroup()));
        payload.put("computeProfile", nullToEmpty(model.getComputeProfile()));
        payload.put("ownerId", model.getOwnerId() == null ? "" : model.getOwnerId().toString());
        payload.put("ownerName", nullToEmpty(model.getOwnerName()));
        payload.put("comment", nullToEmpty(comment));
        payload.put("fieldMapping", fieldMappingPayload(model.getId()));
        outboxPublisher.publish(DomainEvents.MODELING_MODEL_PUBLISHED, model.getId().toString(), payload);
        return toDTO(model);
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
        UUID tenantId,
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
                    null,
                    null,
                    null,
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
            TermSelection term = resolveTermSelection(tenantId, item.termId(), item.termCode(), item.termName());
            drafts.add(new ColumnDraft(
                sourceName,
                targetName,
                StringUtils.hasText(item.sourceType()) ? item.sourceType() : source.type(),
                StringUtils.hasText(item.targetType()) ? item.targetType() : source.type(),
                blankToNull(item.expression()),
                Boolean.TRUE.equals(item.primaryKey()),
                firstText(item.classification(), source.classification()),
                firstText(item.piiType(), source.piiType()),
                firstText(item.suggestLevel(), firstText(source.suggestLevel(), term.sensitivityLevel())),
                term.termId(),
                term.termCode(),
                term.termName(),
                i++
            ));
        }
        return drafts;
    }

    private void saveMappings(DataModel model, List<ColumnDraft> mappings) {
        for (ColumnDraft item : mappings) {
            DataModelColumnMapping mapping = new DataModelColumnMapping();
            mapping.setModelId(model.getId());
            mapping.setSourceColumn(item.source());
            mapping.setTargetColumn(item.target());
            mapping.setSourceType(item.sourceType());
            mapping.setTargetType(item.targetType());
            mapping.setExpression(item.expression());
            mapping.setPrimaryKey(item.primaryKey());
            mapping.setClassification(item.classification());
            mapping.setPiiType(item.piiType());
            mapping.setSuggestLevel(item.suggestLevel());
            mapping.setTermId(item.termId());
            mapping.setTermCode(item.termCode());
            mapping.setTermName(item.termName());
            mapping.setSortNo(item.sortNo());
            mappingRepo.save(mapping);
            upsertTermBinding(model, item);
        }
    }

    private TermSelection resolveTermSelection(UUID tenantId, UUID termId, String termCode, String termName) {
        if (termId == null && !StringUtils.hasText(termCode)) {
            return TermSelection.empty(termCode, termName);
        }
        try {
            Map<String, Object> row = termId != null
                ? jdbc.queryForMap("""
                    SELECT id, code, name, sensitivity_level
                    FROM modeling.business_term
                    WHERE tenant_id = ? AND id = ? AND status = 'APPROVED'
                    """, tenantId, termId)
                : jdbc.queryForMap("""
                    SELECT id, code, name, sensitivity_level
                    FROM modeling.business_term
                    WHERE tenant_id = ? AND lower(code) = lower(?) AND status = 'APPROVED'
                    """, tenantId, termCode.trim());
            return new TermSelection(
                row.get("id") instanceof UUID id ? id : UUID.fromString(String.valueOf(row.get("id"))),
                String.valueOf(row.get("code")),
                String.valueOf(row.get("name")),
                row.get("sensitivity_level") == null ? null : String.valueOf(row.get("sensitivity_level"))
            );
        } catch (EmptyResultDataAccessException e) {
            throw new BizException(40053, "字段映射引用了不存在或未审定的业务术语: " + (termId != null ? termId : termCode));
        }
    }

    private void upsertTermBinding(DataModel model, ColumnDraft item) {
        if (item.termId() == null) {
            return;
        }
        int updated = jdbc.update("""
            UPDATE modeling.business_term_binding
            SET asset_id = NULL,
                relation_type = 'DEFINES',
                source = 'MODELING',
                confidence = 0.95,
                status = 'ACTIVE',
                updated_at = now()
            WHERE tenant_id = ?
              AND term_id = ?
              AND asset_fqn = ?
              AND coalesce(column_name, '') = coalesce(?, '')
            """,
            model.getTenantId(),
            item.termId(),
            model.getTargetFqn(),
            item.target()
        );
        if (updated == 0) {
            jdbc.update("""
                INSERT INTO modeling.business_term_binding
                    (tenant_id, term_id, asset_id, asset_fqn, column_name, relation_type, source, confidence, status, created_by, created_at, updated_at)
                VALUES (?, ?, NULL, ?, ?, 'DEFINES', 'MODELING', 0.95, 'ACTIVE', ?, now(), now())
                """,
                model.getTenantId(),
                item.termId(),
                model.getTargetFqn(),
                item.target(),
                TenantContext.getUserId()
            );
        }
        createPiiCandidateIfSensitive(model, item);
    }

    private void createPiiCandidateIfSensitive(DataModel model, ColumnDraft item) {
        if (!isSensitive(item.suggestLevel()) && !isSensitive(item.classification())) {
            return;
        }
        jdbc.update("""
            INSERT INTO security.pii_scan_record
                (tenant_id, fqn, pii_type, confidence, suggest_level, status, scanned_at)
            VALUES (?, ?, ?, ?, ?, 'PENDING', now())
            ON CONFLICT (tenant_id, fqn)
            DO UPDATE SET
                pii_type = EXCLUDED.pii_type,
                confidence = GREATEST(security.pii_scan_record.confidence, EXCLUDED.confidence),
                suggest_level = EXCLUDED.suggest_level,
                status = CASE
                    WHEN security.pii_scan_record.status = 'IGNORED' THEN 'PENDING'
                    ELSE security.pii_scan_record.status
                END,
                scanned_at = now()
            """,
            model.getTenantId(),
            model.getTargetFqn() + "." + item.target(),
            limit(StringUtils.hasText(item.termName()) ? item.termName() : firstText(item.piiType(), "业务术语敏感字段"), 32),
            0.86d,
            firstText(item.suggestLevel(), item.classification())
        );
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
                m.getTermId(),
                m.getTermCode(),
                m.getTermName(),
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

    private String compileSql(String sourceFqn, List<ColumnDraft> mappings, Map<String, Object> operatorGraph) {
        List<LookupJoinSpec> joins = lookupJoinSpecs(operatorGraph);
        Map<String, String> dictionaryExpressions = dictionaryExpressions(requireTenant(), operatorGraph);
        StringBuilder sql = new StringBuilder("select\n");
        for (int i = 0; i < mappings.size(); i++) {
            ColumnDraft mapping = mappings.get(i);
            String expression = StringUtils.hasText(dictionaryExpressions.get(mapping.target()))
                ? dictionaryExpressions.get(mapping.target())
                : StringUtils.hasText(mapping.expression())
                ? mapping.expression()
                : qualify("src", mapping.source());
            sql.append("  ").append(expression).append(" as ").append(quote(mapping.target()));
            if (i < mappings.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        sql.append("from ").append(sourceFqn).append(" as src");
        for (LookupJoinSpec join : joins) {
            sql.append("\nleft join ")
                .append(join.lookupFqn())
                .append(" as ").append(join.alias())
                .append(" on ").append(qualify("src", join.leftKey()))
                .append(" = ").append(qualify(join.alias(), join.rightKey()));
        }
        return sql.toString();
    }

    private String generateModelSql(
        DataModel model,
        List<DataModelColumnMapping> mappings,
        String sourceSchema,
        String sourceTable,
        Map<String, Object> operatorGraph
    ) {
        List<LookupJoinSpec> joins = lookupJoinSpecs(operatorGraph);
        Map<String, String> dictionaryExpressions = dictionaryExpressions(model.getTenantId(), operatorGraph);
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
            String expression = StringUtils.hasText(dictionaryExpressions.get(mapping.getTargetColumn()))
                ? dictionaryExpressions.get(mapping.getTargetColumn())
                : StringUtils.hasText(mapping.getExpression())
                ? mapping.getExpression()
                : qualify("src", mapping.getSourceColumn());
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
            .append("') }} as src\n");
        for (LookupJoinSpec join : joins) {
            sql.append("left join {{ source('")
                .append(sourceSchemaName(join.lookupFqn()))
                .append("', '")
                .append(sourceTableName(join.lookupFqn()))
                .append("') }} as ").append(join.alias())
                .append(" on ").append(qualify("src", join.leftKey()))
                .append(" = ").append(qualify(join.alias(), join.rightKey()))
                .append("\n");
        }
        if ("INCREMENTAL".equalsIgnoreCase(model.getMaterialization())
            && StringUtils.hasText(model.getIncrementalColumn())) {
            String sourceWatermark = qualify("src", model.getIncrementalColumn());
            String targetWatermark = quote(model.getIncrementalColumn());
            sql.append("{% if is_incremental() %}\n")
                .append("where ").append(sourceWatermark)
                .append(" > (select coalesce(max(").append(targetWatermark)
                .append("), timestamp '1970-01-01 00:00:00') from {{ this }})\n")
                .append("{% endif %}\n");
        }
        return sql.toString();
    }

    private String generateSourceYaml(
        DataModel model,
        CatalogAsset currentSource,
        Map<String, Object> operatorGraph
    ) {
        Map<String, CatalogAsset> sourcesByFqn = new LinkedHashMap<>();
        Map<String, SourceFreshnessSpec> freshnessByFqn = new LinkedHashMap<>();
        sourcesByFqn.put(currentSource.fqn(), currentSource);
        SourceFreshnessSpec currentFreshness = sourceFreshnessSpec(operatorGraph, currentSource.fqn());
        if (currentFreshness != null) {
            freshnessByFqn.put(currentSource.fqn(), currentFreshness);
        }

        for (LookupJoinSpec join : lookupJoinSpecs(operatorGraph)) {
            if (!sourcesByFqn.containsKey(join.lookupFqn())) {
                sourcesByFqn.put(join.lookupFqn(), loadCatalogAsset(model.getTenantId(), join.lookupFqn()));
            }
        }

        for (DataModel existing : modelRepo.findByTenantIdOrderByCreatedAtDesc(model.getTenantId())) {
            if (!contributesGeneratedSource(existing, model.getId())) {
                continue;
            }
            String sourceFqn = existing.getSourceFqn();
            if (sourcesByFqn.containsKey(sourceFqn)) {
                continue;
            }
            sourcesByFqn.put(sourceFqn, loadCatalogAsset(model.getTenantId(), sourceFqn));
            SourceFreshnessSpec existingFreshness = sourceFreshnessSpec(
                operatorGraphMap(existing.getOperatorGraph()),
                sourceFqn
            );
            if (existingFreshness != null) {
                freshnessByFqn.put(sourceFqn, existingFreshness);
            }
        }

        List<CatalogAsset> sources = sourcesByFqn.values().stream()
            .sorted(Comparator.comparing(CatalogAsset::fqn))
            .toList();
        return generateSourceYaml(sources, freshnessByFqn);
    }

    private boolean contributesGeneratedSource(DataModel model, UUID currentModelId) {
        return model != null
            && model.getId() != null
            && !model.getId().equals(currentModelId)
            && "DWD".equalsIgnoreCase(model.getLayer())
            && "VALIDATED".equalsIgnoreCase(model.getStatus())
            && StringUtils.hasText(model.getArtifactPath())
            && StringUtils.hasText(model.getSourceFqn());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> operatorGraphMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Object parsed = JsonUtil.fromJson(raw, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        return Map.of();
    }

    private Map<String, String> dictionaryExpressions(UUID tenantId, Map<String, Object> operatorGraph) {
        Object nodesValue = operatorGraph == null ? null : operatorGraph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return Map.of();
        }
        Map<String, String> expressions = new LinkedHashMap<>();
        for (Object nodeValue : nodes) {
            if (!(nodeValue instanceof Map<?, ?> node)) {
                continue;
            }
            Object configValue = node.get("config");
            Map<?, ?> config = configValue instanceof Map<?, ?> map ? map : Map.of();
            if (!isDictionaryMappingNode(node, config)) {
                continue;
            }
            String sourceColumn = firstText(
                config.get("column"),
                config.get("sourceColumn"),
                config.get("source"),
                config.get("field")
            );
            String targetColumn = firstText(
                config.get("outputColumn"),
                config.get("targetColumn"),
                config.get("target"),
                config.get("field")
            );
            if (!StringUtils.hasText(sourceColumn) || !StringUtils.hasText(targetColumn)) {
                throw new BizException(40062, "字典匹配算子必须配置 column 和 outputColumn");
            }
            String dictionaryRef = firstText(
                config.get("dictionaryRef"),
                config.get("dictionaryCode"),
                config.get("codebookCode"),
                config.get("codebook")
            );
            String dictionaryVersion = firstText(config.get("dictionaryVersion"), config.get("version"));
            List<DictionaryPair> pairs = publishedCodebookPairs(tenantId, dictionaryRef, dictionaryVersion);
            if (pairs.isEmpty()) {
                pairs = dictionaryPairs(config.get("pairs"));
            }
            if (pairs.isEmpty()) {
                String suffix = StringUtils.hasText(dictionaryRef) ? " " + dictionaryRef : "";
                throw new BizException(40062, "字典匹配算子缺少已发布字典或字典项:" + suffix);
            }
            expressions.put(targetColumn, dictionaryCaseExpression(sourceColumn, pairs, firstText(config.get("noMatchPolicy"))));
        }
        return expressions;
    }

    private List<DictionaryPair> publishedCodebookPairs(UUID tenantId, String code, String version) {
        if (tenantId == null || !StringUtils.hasText(code)) {
            return List.of();
        }
        if (StringUtils.hasText(version)) {
            try {
                String entries = jdbc.queryForObject("""
                    SELECT cv.entries::text
                    FROM modeling.codebook cb
                    JOIN modeling.codebook_version cv
                      ON cv.tenant_id = cb.tenant_id AND cv.codebook_id = cb.id
                    WHERE cb.tenant_id = ?
                      AND lower(cb.code) = lower(?)
                      AND cb.status = 'PUBLISHED'
                      AND lower(cv.version) = lower(?)
                    """, String.class, tenantId, code.trim(), version.trim());
                List<DictionaryPair> pairs = dictionaryPairs(entries);
                if (!pairs.isEmpty()) {
                    return pairs;
                }
            } catch (EmptyResultDataAccessException ignored) {
                // Fall back to the current published snapshot or inline pairs for older drafts.
            }
        }
        try {
            String entries = jdbc.queryForObject("""
                SELECT entries::text
                FROM modeling.codebook
                WHERE tenant_id = ?
                  AND lower(code) = lower(?)
                  AND status = 'PUBLISHED'
                """, String.class, tenantId, code.trim());
            return dictionaryPairs(entries);
        } catch (EmptyResultDataAccessException ignored) {
            return List.of();
        }
    }

    private List<DictionaryPair> dictionaryPairs(Object raw) {
        if (raw instanceof String text) {
            if (!StringUtils.hasText(text)) {
                return List.of();
            }
            try {
                return dictionaryPairs(JsonUtil.parse(text));
            } catch (RuntimeException ignored) {
                return List.of();
            }
        }
        if (raw instanceof JsonNode node) {
            if (!node.isArray()) {
                return List.of();
            }
            List<DictionaryPair> pairs = new ArrayList<>();
            for (JsonNode item : node) {
                DictionaryPair pair = dictionaryPair(
                    item.path("from").asText(null),
                    item.path("to").asText(null)
                );
                if (pair != null) {
                    pairs.add(pair);
                }
            }
            return pairs;
        }
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<DictionaryPair> pairs = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            DictionaryPair pair = dictionaryPair(
                firstText(map.get("from"), map.get("source"), map.get("value"), map.get("code")),
                firstText(map.get("to"), map.get("target"), map.get("label"), map.get("name"))
            );
            if (pair != null) {
                pairs.add(pair);
            }
        }
        return pairs;
    }

    private DictionaryPair dictionaryPair(String from, String to) {
        if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
            return null;
        }
        return new DictionaryPair(from.trim(), to.trim());
    }

    private String dictionaryCaseExpression(String sourceColumn, List<DictionaryPair> pairs, String noMatchPolicy) {
        String sourceExpression = qualify("src", sourceColumn);
        String elseExpression = "NULL".equalsIgnoreCase(noMatchPolicy) ? "null" : sourceExpression;
        StringBuilder expression = new StringBuilder("case");
        for (DictionaryPair pair : pairs) {
            expression.append(" when ")
                .append(sourceExpression)
                .append(" = ")
                .append(sqlString(pair.from()))
                .append(" then ")
                .append(sqlString(pair.to()));
        }
        expression.append(" else ").append(elseExpression).append(" end");
        return expression.toString();
    }

    private String sqlString(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    private SourceFreshnessSpec sourceFreshnessSpec(Map<String, Object> operatorGraph, String sourceFqn) {
        Object nodesValue = operatorGraph == null ? null : operatorGraph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return null;
        }
        for (Object nodeValue : nodes) {
            if (!(nodeValue instanceof Map<?, ?> node) || !isQualityGateNode(node)) {
                continue;
            }
            Object configValue = node.get("config");
            if (!(configValue instanceof Map<?, ?> config) || !isFreshnessGateNode(node, config)) {
                continue;
            }
            String nodeSourceFqn = firstText(config.get("sourceFqn"), config.get("assetFqn"));
            if (StringUtils.hasText(nodeSourceFqn) && !nodeSourceFqn.equalsIgnoreCase(sourceFqn)) {
                continue;
            }
            String loadedAtField = firstText(
                config.get("loadedAtField"),
                config.get("loaded_at_field"),
                config.get("column"),
                config.get("timestampColumn"),
                config.get("watermarkColumn")
            );
            if (!StringUtils.hasText(loadedAtField)) {
                continue;
            }

            FreshnessThreshold warnAfter = freshnessThreshold(firstPresent(config.get("warnAfter"), config.get("warn_after")));
            FreshnessThreshold errorAfter = freshnessThreshold(firstPresent(config.get("errorAfter"), config.get("error_after")));
            if (warnAfter == null && errorAfter == null) {
                FreshnessThreshold maxDelay = freshnessThreshold(firstPresent(config.get("maxDelay"), config.get("max_delay")));
                if (maxDelay == null) {
                    continue;
                }
                String action = firstText(config.get("actionOnViolation"), config.get("action"), config.get("severity"));
                if ("WARN".equalsIgnoreCase(action)) {
                    warnAfter = maxDelay;
                } else {
                    errorAfter = maxDelay;
                }
            }
            return new SourceFreshnessSpec(loadedAtField, warnAfter, errorAfter);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeStoredQualityGateNodes(
        Map<String, Object> generatedGraph,
        Map<String, Object> storedGraph
    ) {
        Object generatedNodesValue = generatedGraph.get("nodes");
        Object storedNodesValue = storedGraph == null ? null : storedGraph.get("nodes");
        if (!(generatedNodesValue instanceof List<?> generatedNodes)
            || !(storedNodesValue instanceof List<?> storedNodes)) {
            return generatedGraph;
        }
        List<Object> mergedNodes = new ArrayList<>(generatedNodes);
        Set<String> nodeIds = new java.util.LinkedHashSet<>();
        for (Object nodeValue : generatedNodes) {
            if (nodeValue instanceof Map<?, ?> node) {
                String id = stringValue(node.get("id"));
                if (StringUtils.hasText(id)) {
                    nodeIds.add(id);
                }
            }
        }
        for (Object nodeValue : storedNodes) {
            if (!(nodeValue instanceof Map<?, ?> node) || !isPreservedQualityGateNode(node)) {
                continue;
            }
            String id = stringValue(node.get("id"));
            if (!StringUtils.hasText(id) || nodeIds.contains(id)) {
                continue;
            }
            mergedNodes.add((Map<String, Object>) node);
            nodeIds.add(id);
        }
        generatedGraph.put("nodes", mergedNodes);
        return generatedGraph;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeStoredGovernanceNodes(
        Map<String, Object> generatedGraph,
        Map<String, Object> storedGraph
    ) {
        Object generatedNodesValue = generatedGraph.get("nodes");
        Object storedNodesValue = storedGraph == null ? null : storedGraph.get("nodes");
        if (!(generatedNodesValue instanceof List<?> generatedNodes)
            || !(storedNodesValue instanceof List<?> storedNodes)) {
            return generatedGraph;
        }

        List<Object> mergedNodes = new ArrayList<>(generatedNodes);
        Set<String> nodeIds = new java.util.LinkedHashSet<>();
        for (Object nodeValue : generatedNodes) {
            if (nodeValue instanceof Map<?, ?> node) {
                String id = stringValue(node.get("id"));
                if (StringUtils.hasText(id)) {
                    nodeIds.add(id);
                }
            }
        }
        for (Object nodeValue : storedNodes) {
            if (!(nodeValue instanceof Map<?, ?> node)) {
                continue;
            }
            Object configValue = node.get("config");
            Map<?, ?> config = configValue instanceof Map<?, ?> map ? map : Map.of();
            if (!isAdvancedGovernanceNode(node, config)) {
                continue;
            }
            String id = stringValue(node.get("id"));
            if (!StringUtils.hasText(id) || nodeIds.contains(id)) {
                continue;
            }
            mergedNodes.add((Map<String, Object>) node);
            nodeIds.add(id);
        }
        generatedGraph.put("nodes", mergedNodes);

        Object generatedEdgesValue = generatedGraph.get("edges");
        Object storedEdgesValue = storedGraph.get("edges");
        if (generatedEdgesValue instanceof List<?> generatedEdges
            && storedEdgesValue instanceof List<?> storedEdges) {
            List<Object> mergedEdges = new ArrayList<>(generatedEdges);
            Set<String> edgeKeys = new java.util.LinkedHashSet<>();
            for (Object edgeValue : generatedEdges) {
                if (edgeValue instanceof Map<?, ?> edge) {
                    edgeKeys.add(stringValue(edge.get("source")) + "->" + stringValue(edge.get("target")));
                }
            }
            for (Object edgeValue : storedEdges) {
                if (!(edgeValue instanceof Map<?, ?> edge)) {
                    continue;
                }
                String source = stringValue(edge.get("source"));
                String target = stringValue(edge.get("target"));
                String key = source + "->" + target;
                if (nodeIds.contains(source) && nodeIds.contains(target) && !edgeKeys.contains(key)) {
                    mergedEdges.add((Map<String, Object>) edge);
                    edgeKeys.add(key);
                }
            }
            generatedGraph.put("edges", mergedEdges);
        }
        return generatedGraph;
    }

    private boolean isAdvancedGovernanceNode(Map<?, ?> node, Map<?, ?> config) {
        return isDictionaryMappingNode(node, config) || isLookupJoinNode(node, config);
    }

    private boolean isDictionaryMappingNode(Map<?, ?> node, Map<?, ?> config) {
        String ref = nodeRef(node, config);
        return ref.contains("codebook")
            || ref.contains("dictionary")
            || ref.contains("dict_mapping")
            || ref.contains("standard.codebook_mapping");
    }

    private boolean isLookupJoinNode(Map<?, ?> node, Map<?, ?> config) {
        String ref = nodeRef(node, config);
        return ref.contains("lookup")
            || ref.contains("reference_join")
            || ref.contains("join.lookup")
            || "lookup_join".equals(ref);
    }

    private String nodeRef(Map<?, ?> node, Map<?, ?> config) {
        return stringValue(firstText(node.get("operatorRef"), config.get("operatorRef"), config.get("type"), node.get("type")))
            .toLowerCase(Locale.ROOT)
            .replace('-', '_');
    }

    private boolean isPreservedQualityGateNode(Map<?, ?> node) {
        Object configValue = node.get("config");
        if (!(configValue instanceof Map<?, ?> config) || !isQualityGateNode(node)) {
            return false;
        }
        return isFreshnessGateNode(node, config) || isCustomSqlGateNode(node, config);
    }

    private boolean isFreshnessGateNode(Map<?, ?> node, Map<?, ?> config) {
        return isFreshnessRef(node.get("operatorRef"))
            || isFreshnessRef(config.get("type"))
            || isFreshnessRef(config.get("test"))
            || stringList(config.get("tests")).stream().anyMatch(this::isFreshnessRef);
    }

    private boolean isCustomSqlGateNode(Map<?, ?> node, Map<?, ?> config) {
        return isCustomSqlRef(node.get("operatorRef"))
            || isCustomSqlRef(config.get("type"))
            || isCustomSqlRef(config.get("test"))
            || stringList(config.get("tests")).stream().anyMatch(this::isCustomSqlRef);
    }

    private boolean isFreshnessRef(Object value) {
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        if (text.startsWith("gate.")) {
            text = text.substring("gate.".length());
        }
        text = text.replace("-", "_");
        return "freshness".equals(text) || "source_freshness".equals(text);
    }

    private boolean isCustomSqlRef(Object value) {
        String text = stringValue(value).toLowerCase(Locale.ROOT);
        if (text.startsWith("gate.")) {
            text = text.substring("gate.".length());
        }
        text = text.replace("-", "_");
        return "custom_sql".equals(text) || "customsql".equals(text);
    }

    private FreshnessThreshold freshnessThreshold(Object value) {
        if (value instanceof Map<?, ?> map) {
            Integer count = integerValue(map.get("count"));
            String period = normalizeFreshnessPeriod(firstText(map.get("period"), map.get("unit")));
            return count == null || !StringUtils.hasText(period) ? null : new FreshnessThreshold(count, period);
        }
        String text = stringValue(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        var matcher = FRESHNESS_DELAY.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String period = normalizeFreshnessPeriod(matcher.group(2));
        return StringUtils.hasText(period)
            ? new FreshnessThreshold(Integer.parseInt(matcher.group(1)), period)
            : null;
    }

    private String normalizeFreshnessPeriod(String value) {
        String normalized = stringValue(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "m", "min", "mins", "minute", "minutes" -> "minute";
            case "h", "hr", "hrs", "hour", "hours" -> "hour";
            case "d", "day", "days" -> "day";
            case "w", "week", "weeks" -> "week";
            default -> null;
        };
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        if (!text.matches("\\d+")) {
            return null;
        }
        return Integer.parseInt(text);
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String generateSourceYaml(List<CatalogAsset> sources, Map<String, SourceFreshnessSpec> freshnessByFqn) {
        Map<String, List<CatalogAsset>> sourcesBySchema = new LinkedHashMap<>();
        for (CatalogAsset source : sources) {
            sourcesBySchema
                .computeIfAbsent(sourceSchemaName(source.fqn()), ignored -> new ArrayList<>())
                .add(source);
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 2\n");
        yaml.append("sources:\n");
        sourcesBySchema.forEach((sourceSchema, schemaSources) -> {
            yaml.append("  - name: ").append(sourceSchema).append("\n");
            yaml.append("    schema: ").append(sourceSchema).append("\n");
            yaml.append("    description: ")
                .append(yamlQuote("OneLake generated sources for " + sourceSchema))
                .append("\n");
            yaml.append("    tables:\n");
            schemaSources.stream()
                .sorted(Comparator.comparing(source -> sourceTableName(source.fqn())))
                .forEach(source -> {
                    String sourceTable = sourceTableName(source.fqn());
                    yaml.append("      - name: ").append(sourceTable).append("\n");
                    yaml.append("        description: ")
                        .append(yamlQuote("Generated from Catalog asset " + source.fqn()))
                        .append("\n");
                    SourceFreshnessSpec freshness = freshnessByFqn.get(source.fqn());
                    if (freshness != null) {
                        yaml.append("        loaded_at_field: ").append(yamlQuote(freshness.loadedAtField())).append("\n");
                        yaml.append("        freshness:\n");
                        appendFreshnessThresholdYaml(yaml, "warn_after", freshness.warnAfter());
                        appendFreshnessThresholdYaml(yaml, "error_after", freshness.errorAfter());
                    }
                    yaml.append("        columns:\n");
                    for (CatalogColumn column : source.columns()) {
                        yaml.append("          - name: ").append(column.name()).append("\n");
                        yaml.append("            description: ")
                            .append(yamlQuote("Source column " + column.name() + " (" + column.type() + ")"))
                            .append("\n");
                    }
                });
        });
        return yaml.toString();
    }

    private void appendFreshnessThresholdYaml(
        StringBuilder yaml,
        String key,
        FreshnessThreshold threshold
    ) {
        if (threshold == null) {
            return;
        }
        yaml.append("          ").append(key).append(":\n");
        yaml.append("            count: ").append(threshold.count()).append("\n");
        yaml.append("            period: ").append(threshold.period()).append("\n");
    }

    private String generateSchemaYaml(
        DataModel model,
        List<DataModelColumnMapping> mappings,
        String dbtModelName,
        Map<String, Object> operatorGraph
    ) {
        List<DbtTestSpec> modelTests = qualityGateModelDbtTests(operatorGraph);
        Map<String, List<DbtTestSpec>> testsByColumn = qualityGateDbtTests(operatorGraph);
        if (testsByColumn.isEmpty()) {
            testsByColumn = primaryKeyDbtTests(mappings);
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 2\n");
        yaml.append("models:\n");
        yaml.append("  - name: ").append(dbtModelName).append("\n");
        yaml.append("    description: ").append(yamlQuote("OneLake generated DWD model from " + model.getSourceFqn())).append("\n");
        if (!modelTests.isEmpty()) {
            yaml.append("    tests:\n");
            for (DbtTestSpec test : modelTests) {
                appendDbtTestYaml(yaml, test, "      ", "          ", "            ", "              ");
            }
        }
        yaml.append("    columns:\n");
        for (DataModelColumnMapping mapping : mappings) {
            yaml.append("      - name: ").append(mapping.getTargetColumn()).append("\n");
            yaml.append("        description: ")
                .append(yamlQuote("Mapped from " + mapping.getSourceColumn()))
                .append("\n");
            List<DbtTestSpec> tests = testsByColumn.getOrDefault(mapping.getTargetColumn(), List.of());
            if (!tests.isEmpty()) {
                yaml.append("        tests:\n");
                for (DbtTestSpec test : tests) {
                    appendDbtTestYaml(yaml, test);
                }
            }
        }
        return yaml.toString();
    }

    private Map<String, List<DbtTestSpec>> primaryKeyDbtTests(List<DataModelColumnMapping> mappings) {
        Map<String, List<DbtTestSpec>> testsByColumn = new LinkedHashMap<>();
        for (DataModelColumnMapping mapping : mappings) {
            if (Boolean.TRUE.equals(mapping.getPrimaryKey())) {
                testsByColumn.put(mapping.getTargetColumn(), List.of(
                    new DbtTestSpec("not_null", Map.of()),
                    new DbtTestSpec("unique", Map.of())
                ));
            }
        }
        return testsByColumn;
    }

    private Map<String, List<DbtTestSpec>> qualityGateDbtTests(Map<String, Object> operatorGraph) {
        Object nodesValue = operatorGraph == null ? null : operatorGraph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return Map.of();
        }

        Map<String, List<DbtTestSpec>> testsByColumn = new LinkedHashMap<>();
        for (Object nodeValue : nodes) {
            if (!(nodeValue instanceof Map<?, ?> node)) {
                continue;
            }
            if (!isQualityGateNode(node)) {
                continue;
            }
            Object configValue = node.get("config");
            if (!(configValue instanceof Map<?, ?> config)) {
                continue;
            }
            List<String> columns = stringList(config.get("columns"));
            if (columns.isEmpty()) {
                columns = stringList(config.get("column"));
            }
            List<DbtTestSpec> tests = dbtTestSpecs(config.get("tests"), config);
            if (tests.isEmpty()) {
                tests = dbtTestSpecs(node.get("operatorRef"), config);
            }
            if (columns.isEmpty() || tests.isEmpty()) {
                continue;
            }
            for (String column : columns) {
                if (!StringUtils.hasText(column)) {
                    continue;
                }
                List<DbtTestSpec> columnTests = testsByColumn.computeIfAbsent(column, key -> new ArrayList<>());
                for (DbtTestSpec test : tests) {
                    if (isModelLevelDbtTest(test)) {
                        continue;
                    }
                    if (!columnTests.contains(test)) {
                        columnTests.add(test);
                    }
                }
            }
        }
        return testsByColumn;
    }

    private List<DbtTestSpec> qualityGateModelDbtTests(Map<String, Object> operatorGraph) {
        Object nodesValue = operatorGraph == null ? null : operatorGraph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return List.of();
        }

        List<DbtTestSpec> tests = new ArrayList<>();
        for (Object nodeValue : nodes) {
            if (!(nodeValue instanceof Map<?, ?> node) || !isQualityGateNode(node)) {
                continue;
            }
            Object configValue = node.get("config");
            if (!(configValue instanceof Map<?, ?> config)) {
                continue;
            }
            List<DbtTestSpec> nodeTests = dbtTestSpecs(config.get("tests"), config);
            if (nodeTests.isEmpty()) {
                nodeTests = dbtTestSpecs(node.get("operatorRef"), config);
            }
            for (DbtTestSpec test : nodeTests) {
                if (isModelLevelDbtTest(test) && !tests.contains(test)) {
                    tests.add(test);
                }
            }
        }
        return tests;
    }

    private boolean isModelLevelDbtTest(DbtTestSpec test) {
        return "onelake_row_count".equals(test.name())
            || "onelake_custom_sql".equals(test.name());
    }

    private boolean isQualityGateNode(Map<?, ?> node) {
        String nodeType = stringValue(node.get("nodeType"));
        String type = stringValue(node.get("type"));
        String category = stringValue(node.get("operatorCategory"));
        String operatorRef = stringValue(node.get("operatorRef"));
        return "QUALITY_GATE".equalsIgnoreCase(nodeType)
            || "QUALITY_GATE".equalsIgnoreCase(type)
            || "QUALITY_GATE".equalsIgnoreCase(category)
            || operatorRef.startsWith("gate.");
    }

    private List<DbtTestSpec> dbtTestSpecs(Object value, Map<?, ?> config) {
        return stringList(value).stream()
            .map(this::normalizeDbtTestName)
            .filter(StringUtils::hasText)
            .map(testName -> dbtTestSpec(testName, config))
            .filter(spec -> spec != null)
            .toList();
    }

    private String normalizeDbtTestName(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("gate.")) {
            normalized = normalized.substring("gate.".length());
        }
        return switch (normalized) {
            case "not_null", "unique" -> normalized;
            case "enum", "accepted_values" -> "accepted_values";
            case "referential", "relationships" -> "relationships";
            case "range" -> "onelake_range";
            case "regex" -> "onelake_regex";
            case "row_count", "rowcount" -> "onelake_row_count";
            case "custom_sql", "customsql" -> "onelake_custom_sql";
            default -> null;
        };
    }

    private DbtTestSpec dbtTestSpec(String testName, Map<?, ?> config) {
        if ("accepted_values".equals(testName)) {
            List<String> values = stringList(config.get("values"));
            if (values.isEmpty()) {
                return null;
            }
            return new DbtTestSpec(testName, Map.of("values", values));
        }
        if ("relationships".equals(testName)) {
            String refModel = stringValue(config.get("refModel"));
            String refColumn = stringValue(config.get("refColumn"));
            if (!StringUtils.hasText(refModel) || !StringUtils.hasText(refColumn)) {
                return null;
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("to", new YamlRaw("ref('" + refModel.replace("'", "''") + "')"));
            arguments.put("field", new YamlRaw(refColumn));
            return new DbtTestSpec(testName, arguments);
        }
        if ("onelake_range".equals(testName)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            Object min = firstPresent(config.get("min"), config.get("minValue"), config.get("min_value"));
            Object max = firstPresent(config.get("max"), config.get("maxValue"), config.get("max_value"));
            if (min != null) {
                arguments.put("min_value", yamlRawIfNumeric(min));
            }
            if (max != null) {
                arguments.put("max_value", yamlRawIfNumeric(max));
            }
            return arguments.isEmpty() ? null : new DbtTestSpec(testName, arguments);
        }
        if ("onelake_regex".equals(testName)) {
            String pattern = stringValue(config.get("pattern"));
            if (!StringUtils.hasText(pattern)) {
                return null;
            }
            return new DbtTestSpec(testName, Map.of("pattern", pattern));
        }
        if ("onelake_row_count".equals(testName)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            Object min = firstPresent(config.get("min"), config.get("minValue"), config.get("min_value"));
            Object max = firstPresent(config.get("max"), config.get("maxValue"), config.get("max_value"));
            if (min != null) {
                arguments.put("min_value", yamlRawIfNumeric(min));
            }
            if (max != null) {
                arguments.put("max_value", yamlRawIfNumeric(max));
            }
            return arguments.isEmpty() ? null : new DbtTestSpec(testName, arguments);
        }
        if ("onelake_custom_sql".equals(testName)) {
            String assertionSql = firstText(config.get("assertionSql"), config.get("assertion_sql"), config.get("sql"));
            if (!StringUtils.hasText(assertionSql)) {
                return null;
            }
            return new DbtTestSpec(testName, Map.of("assertion_sql", normalizeCustomAssertionSql(assertionSql)));
        }
        return new DbtTestSpec(testName, Map.of());
    }

    private String normalizeCustomAssertionSql(String assertionSql) {
        String normalized = assertionSql
            .replace("{{ model }}", CUSTOM_SQL_TEMPLATE_PLACEHOLDER)
            .replace("{{model}}", CUSTOM_SQL_TEMPLATE_PLACEHOLDER)
            .trim();
        if (!normalized.contains(CUSTOM_SQL_TEMPLATE_PLACEHOLDER)) {
            throw new BizException(40056, "自定义 SQL 门禁必须使用 {{ model }} 占位符引用当前模型");
        }
        String parseSql = normalized.replace(CUSTOM_SQL_TEMPLATE_PLACEHOLDER, "onelake_model_under_test");
        Statement statement = ReadOnlySqlValidator.requireSingleReadOnlyStatement(
            parseSql,
            40056,
            "自定义 SQL 门禁只允许单条只读 SELECT 断言",
            "自定义 SQL 门禁只允许单条 SQL"
        );
        Set<String> referencedTables = ReadOnlySqlValidator.referencedTables(statement);
        boolean onlyCurrentModel = referencedTables.stream()
            .map(table -> table.replace("\"", "").toLowerCase(Locale.ROOT))
            .allMatch("onelake_model_under_test"::equals);
        if (!onlyCurrentModel) {
            throw new BizException(40056, "自定义 SQL 门禁只能引用当前模型 {{ model }}");
        }
        return normalized;
    }

    private void appendDbtTestYaml(StringBuilder yaml, DbtTestSpec test) {
        appendDbtTestYaml(yaml, test, "          ", "              ", "                ", "                  ");
    }

    private void appendDbtTestYaml(
        StringBuilder yaml,
        DbtTestSpec test,
        String testIndent,
        String argumentsIndent,
        String argumentKeyIndent,
        String listValueIndent
    ) {
        if (test.arguments().isEmpty()) {
            yaml.append(testIndent).append("- ").append(test.name()).append("\n");
            return;
        }
        yaml.append(testIndent).append("- ").append(test.name()).append(":\n");
        yaml.append(argumentsIndent).append("arguments:\n");
        for (Map.Entry<String, Object> entry : test.arguments().entrySet()) {
            if (entry.getValue() instanceof List<?> values) {
                yaml.append(argumentKeyIndent).append(entry.getKey()).append(":\n");
                for (Object value : values) {
                    yaml.append(listValueIndent).append("- ").append(yamlQuote(stringValue(value))).append("\n");
                }
            } else {
                yaml.append(argumentKeyIndent).append(entry.getKey()).append(": ")
                    .append(yamlScalar(entry.getValue()))
                    .append("\n");
            }
        }
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null && StringUtils.hasText(stringValue(value))) {
                return value;
            }
        }
        return null;
    }

    private Object yamlRawIfNumeric(Object value) {
        String text = stringValue(value);
        if (text.matches("-?\\d+(\\.\\d+)?")) {
            return new YamlRaw(text);
        }
        return value;
    }

    private String yamlScalar(Object value) {
        if (value instanceof YamlRaw raw) {
            return raw.value();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return yamlQuote(stringValue(value));
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(StringUtils::hasText)
                .toList();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return List.of(text.trim());
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private UUID ensureDwdDag(
        DataModel model,
        String sqlPath,
        String schemaPath,
        String sourcePath,
        List<String> outputColumns,
        Map<String, Object> operatorGraph
    ) {
        String dagsterJob = StringUtils.hasText(model.getDagsterJob()) ? model.getDagsterJob() : "onelake_pipeline_run";
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
        definition.put("kind", "SPARK_GOVERNANCE_DAG");
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
        List<String> outputColumns,
        Map<String, ResolvedOperator> operators
    ) {
        List<String> primaryKeys = mappings.stream()
            .filter(mapping -> Boolean.TRUE.equals(mapping.getPrimaryKey()))
            .map(DataModelColumnMapping::getTargetColumn)
            .toList();
        List<String> sensitiveColumns = mappings.stream()
            .filter(mapping -> isSensitive(mapping.getClassification()) || isSensitive(mapping.getSuggestLevel()))
            .map(DataModelColumnMapping::getTargetColumn)
            .toList();

        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node(
            "input_ods", "INPUT", model.getSourceFqn(),
            Map.of("assetFqn", model.getSourceFqn(), "sourceFqn", model.getSourceFqn(), "layer", "ODS"),
            operators.get("input_ods")
        ));
        nodes.add(node(
            "transform_mapping", "TRANSFORM", "字段选择与标准化",
            Map.of(
                "sourceFqn", model.getSourceFqn(),
                "targetFqn", model.getTargetFqn(),
                "mapping", mappingConfig(mappings),
                "mappings", mappings.stream().map(m -> Map.of(
                    "source", m.getSourceColumn(),
                    "target", m.getTargetColumn(),
                    "expression", StringUtils.hasText(m.getExpression()) ? m.getExpression() : m.getSourceColumn()
                )).toList()
            ),
            operators.get("transform_mapping")
        ));
        nodes.add(node(
            "govern_clean", "GOVERN", "脏数据过滤与标准治理",
            Map.of(
                "requiredColumns", primaryKeys.isEmpty() ? outputColumns : primaryKeys,
                "policies", List.of(
                    Map.of("type", "PRIMARY_KEY_NOT_NULL", "actionOnViolation", "FAIL"),
                    Map.of("type", "ENUM_STANDARDIZE", "actionOnViolation", "WARN")
                )
            ),
            operators.get("govern_clean")
        ));
        if (!sensitiveColumns.isEmpty()) {
            nodes.add(node(
                "mask_sensitive", "MASK", "敏感字段透传检查",
                Map.of(
                    "actionOnViolation", "WARN",
                    "columns", sensitiveColumns,
                    "keepHead", 3,
                    "keepTail", 4
                ),
                operators.get("mask_sensitive")
            ));
        }
        nodes.add(node(
            "quality_gate", "QUALITY_GATE", "主键与非空门禁",
            Map.of(
                "actionOnViolation", "FAIL",
                "columns", primaryKeys.isEmpty() ? outputColumns : primaryKeys,
                "tests", List.of("not_null", "unique")
            ),
            operators.get("quality_gate")
        ));
        nodes.add(node(
            "spark_sql", "TRANSFORM", "Spark SQL 编译执行",
            Map.of(
                "sql", model.getCompiledSql() == null ? "" : model.getCompiledSql(),
                "modelName", model.getDbtModelName(),
                "targetFqn", model.getTargetFqn(),
                "engine", model.getEngine(),
                "resourceGroup", model.getResourceGroup(),
                "computeProfile", model.getComputeProfile()
            ),
            operators.get("spark_sql")
        ));
        nodes.add(node(
            "output_dwd", "OUTPUT", model.getTargetFqn(),
            outputConfig(model, outputColumns),
            operators.get("output_dwd")
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
        edges.add(Map.of("source", "quality_gate", "target", "spark_sql"));
        edges.add(Map.of("source", "spark_sql", "target", "output_dwd"));

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
        return node(id, nodeType, name, config, null);
    }

    private Map<String, Object> node(
        String id,
        String nodeType,
        String name,
        Map<String, Object> config,
        ResolvedOperator operator
    ) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", nodeType);
        node.put("nodeType", nodeType);
        node.put("name", name);
        if (operator != null) {
            node.put("operatorRef", operator.operatorRef());
            node.put("operatorVersion", operator.version());
            node.put("operatorCategory", operator.category());
            node.put("compileTarget", operator.compileTarget());
            node.put("manifest", operator.manifest());
            node.put("emitsLineage", operator.manifest().path("lineageRule").isObject());
            node.put("emitsQualityResult", operator.manifest().path("emitsQualityResult").asBoolean(false));
            JsonNode policy = operator.manifest().path("policy");
            if (policy.isObject() && policy.size() > 0) {
                node.put("policy", policy);
            }
        }
        node.put("config", config);
        node.put("resourceProfile", Map.of("resourceGroup", "spark-default", "computeProfile", "spark-small"));
        return node;
    }

    private Map<String, ResolvedOperator> resolveDefaultOperators(
        DataModel model,
        List<DataModelColumnMapping> mappings
    ) {
        boolean hasSensitiveColumns = mappings.stream()
            .anyMatch(mapping -> isSensitive(mapping.getClassification()) || isSensitive(mapping.getSuggestLevel()));
        List<DefaultOperatorSpec> specs = new ArrayList<>();
        specs.add(new DefaultOperatorSpec("input_ods", OP_INPUT_ODS_TABLE, "INPUT"));
        specs.add(new DefaultOperatorSpec("transform_mapping", OP_TRANSFORM_RENAME_COLUMNS, "TRANSFORM"));
        specs.add(new DefaultOperatorSpec("govern_clean", OP_GOVERN_DROP_REQUIRED_MISSING, "GOVERN"));
        if (hasSensitiveColumns) {
            specs.add(new DefaultOperatorSpec("mask_sensitive", OP_MASK_PARTIAL, "MASK"));
        }
        specs.add(new DefaultOperatorSpec("quality_gate", OP_GATE_NOT_NULL, "QUALITY_GATE"));
        specs.add(new DefaultOperatorSpec("spark_sql", OP_TRANSFORM_SPARK_SQL, "TRANSFORM"));
        specs.add(new DefaultOperatorSpec("output_dwd", outputOperatorRef(model), "OUTPUT"));

        Map<String, ResolvedOperator> operators = new LinkedHashMap<>();
        for (DefaultOperatorSpec spec : specs) {
            operators.put(spec.nodeId(), loadBuiltInOperatorManifest(spec));
        }
        return operators;
    }

    private ResolvedOperator loadBuiltInOperatorManifest(DefaultOperatorSpec spec) {
        OperatorManifestRow row;
        try {
            row = jdbc.queryForObject("""
                SELECT o.operator_ref, ov.version, o.category, ov.manifest::text AS manifest
                FROM orchestration.operator o
                JOIN orchestration.operator_version ov
                  ON ov.operator_id = o.id
                 AND ov.version = o.latest_version
                WHERE o.operator_ref = ?
                  AND o.scope = 'BUILTIN'
                  AND o.tenant_id IS NULL
                  AND o.status = 'ACTIVE'
                LIMIT 1
                """, (rs, rowNum) -> new OperatorManifestRow(
                    rs.getString("operator_ref"),
                    rs.getString("version"),
                    rs.getString("category"),
                    rs.getString("manifest")
                ), spec.operatorRef());
        } catch (EmptyResultDataAccessException e) {
            throw new BizException(40059, "DWD 默认算子 Manifest 不存在: " + spec.operatorRef());
        } catch (DataAccessException e) {
            throw new BizException(40059, "DWD 默认算子 Manifest 读取失败: " + e.getMessage());
        }
        if (row == null || !StringUtils.hasText(row.manifestJson())) {
            throw new BizException(40059, "DWD 默认算子 Manifest 为空: " + spec.operatorRef());
        }

        JsonNode manifest;
        try {
            manifest = JsonUtil.parse(row.manifestJson());
        } catch (RuntimeException e) {
            throw new BizException(40059, "DWD 默认算子 Manifest JSON 非法: " + spec.operatorRef());
        }
        String manifestRef = manifest.path("operatorRef").asText("");
        String manifestVersion = manifest.path("version").asText(row.version());
        String manifestCategory = manifest.path("category").asText(row.category());
        String compileTarget = manifest.path("compileTarget").asText("");

        List<String> errors = new ArrayList<>();
        if (!spec.operatorRef().equals(manifestRef)) {
            errors.add("operatorRef 不匹配");
        }
        if (!StringUtils.hasText(manifestVersion)) {
            errors.add("version 为空");
        }
        if (!spec.category().equalsIgnoreCase(manifestCategory)) {
            errors.add("category 不匹配");
        }
        if (!"SPARK".equalsIgnoreCase(compileTarget)) {
            errors.add("compileTarget 必须为 SPARK");
        }
        if (!manifest.path("template").isObject()) {
            errors.add("template 缺失");
        }
        if (!errors.isEmpty()) {
            throw new BizException(40059, "DWD 默认算子 Manifest 校验失败: "
                + spec.operatorRef() + " - " + String.join("; ", errors));
        }
        return new ResolvedOperator(manifestRef, manifestVersion, manifestCategory, compileTarget, manifest);
    }

    private String outputOperatorRef(DataModel model) {
        String materialization = normalizeMaterialization(model.getMaterialization());
        return switch (materialization) {
            case "VIEW" -> OP_OUTPUT_VIEW;
            case "INCREMENTAL" -> OP_OUTPUT_INCREMENTAL_MERGE;
            default -> OP_OUTPUT_ICEBERG_TABLE;
        };
    }

    private Map<String, String> mappingConfig(List<DataModelColumnMapping> mappings) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (DataModelColumnMapping item : mappings) {
            mapping.put(item.getSourceColumn(), item.getTargetColumn());
        }
        return mapping;
    }

    private Map<String, Object> outputConfig(DataModel model, List<String> outputColumns) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("assetFqn", model.getTargetFqn());
        config.put("targetFqn", model.getTargetFqn());
        config.put("layer", "DWD");
        config.put("columns", outputColumns);
        if (StringUtils.hasText(model.getPartitionExpr())) {
            config.put("partitionBy", model.getPartitionExpr());
        }
        if (StringUtils.hasText(model.getUniqueKey())) {
            config.put("uniqueKey", model.getUniqueKey());
        }
        if (StringUtils.hasText(model.getIncrementalColumn())) {
            config.put("incrementalColumn", model.getIncrementalColumn());
        }
        if ("INCREMENTAL".equalsIgnoreCase(model.getMaterialization())) {
            config.put("strategy", "merge");
        }
        return config;
    }

    private void applyExecutionDefaults(DataModel model) {
        if (!StringUtils.hasText(model.getPipelineMode())) {
            model.setPipelineMode("SYSTEM_GENERATED");
        }
        if (model.getOperatorGraphVersion() == null) {
            model.setOperatorGraphVersion(1);
        }
        if (!StringUtils.hasText(model.getResourceGroup())) {
            model.setResourceGroup("spark-default");
        }
        if (!StringUtils.hasText(model.getComputeProfile())) {
            model.setComputeProfile("spark-small");
        }
        if (!StringUtils.hasText(model.getEngine())) {
            model.setEngine("SPARK");
        }
        if (!StringUtils.hasText(model.getCostPolicy()) || "{}".equals(model.getCostPolicy())) {
            model.setCostPolicy(defaultCostPolicyJson());
        }
    }

    private void applyDraftExecutionRequest(DataModel model, DwdModelDraftRequest request) {
        if (request == null) {
            return;
        }
        if (StringUtils.hasText(request.pipelineMode())) {
            model.setPipelineMode(request.pipelineMode().trim());
        }
        if (request.operatorGraphVersion() != null) {
            model.setOperatorGraphVersion(request.operatorGraphVersion());
        }
        if (StringUtils.hasText(request.resourceGroup())) {
            model.setResourceGroup(request.resourceGroup().trim());
        }
        if (StringUtils.hasText(request.computeProfile())) {
            model.setComputeProfile(request.computeProfile().trim());
        }
        if (StringUtils.hasText(request.engine())) {
            model.setEngine(request.engine().trim());
        }
        if (StringUtils.hasText(request.costPolicy())) {
            parseJsonObject(request.costPolicy(), "costPolicy");
            model.setCostPolicy(request.costPolicy().trim());
        }
        if (StringUtils.hasText(request.operatorGraph())) {
            parseJsonObject(request.operatorGraph(), "operatorGraph");
            model.setOperatorGraph(request.operatorGraph().trim());
            if (!StringUtils.hasText(request.pipelineMode()) || "SYSTEM_GENERATED".equalsIgnoreCase(model.getPipelineMode())) {
                model.setPipelineMode("SPARK_GOVERNANCE");
            }
        }
    }

    private JsonNode parseJsonObject(String json, String field) {
        try {
            JsonNode node = JsonUtil.parse(json);
            if (!node.isObject()) {
                throw new BizException(40060, field + " 必须是 JSON object");
            }
            return node;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(40060, field + " JSON 非法: " + e.getMessage());
        }
    }

    private List<LookupJoinSpec> lookupJoinSpecs(Map<String, Object> operatorGraph) {
        Object nodesValue = operatorGraph == null ? null : operatorGraph.get("nodes");
        if (!(nodesValue instanceof List<?> nodes)) {
            return List.of();
        }
        List<LookupJoinSpec> specs = new ArrayList<>();
        int index = 0;
        for (Object nodeValue : nodes) {
            if (!(nodeValue instanceof Map<?, ?> node)) {
                continue;
            }
            Object configValue = node.get("config");
            Map<?, ?> config = configValue instanceof Map<?, ?> map ? map : Map.of();
            if (!isLookupJoinNode(node, config)) {
                continue;
            }
            String lookupFqn = firstText(config.get("lookupFqn"), config.get("referenceFqn"), config.get("assetFqn"));
            String leftKey = firstText(config.get("leftKey"), config.get("sourceKey"), config.get("joinLeftKey"));
            String rightKey = firstText(config.get("rightKey"), config.get("lookupKey"), config.get("joinRightKey"));
            if (!StringUtils.hasText(lookupFqn)
                || !StringUtils.hasText(leftKey)
                || !StringUtils.hasText(rightKey)) {
                throw new BizException(40061, "关联查询算子必须配置 lookupFqn、leftKey 和 rightKey");
            }
            String alias = normalizeSqlAlias(firstText(config.get("alias"), node.get("id")), index);
            specs.add(new LookupJoinSpec(lookupFqn, alias, leftKey, rightKey));
            index++;
        }
        return specs;
    }

    private String normalizeSqlAlias(String preferred, int index) {
        String text = StringUtils.hasText(preferred) ? preferred.trim().toLowerCase(Locale.ROOT) : "lk_" + index;
        text = text.replaceAll("[^a-z0-9_]", "_");
        if (!StringUtils.hasText(text)) {
            text = "lk_" + index;
        }
        if (Character.isDigit(text.charAt(0))) {
            text = "lk_" + text;
        }
        return text;
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
            throw new BizException(50050, "DWD Spark 编译产物写入失败: " + e.getMessage());
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
            mapping.getTermId(),
            mapping.getTermCode(),
            mapping.getTermName(),
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

    private String qualify(String alias, String column) {
        if (!StringUtils.hasText(alias)) {
            return quote(column);
        }
        return alias + "." + quote(column);
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

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
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
        UUID termId,
        String termCode,
        String termName,
        int sortNo
    ) {}

    private record TermSelection(
        UUID termId,
        String termCode,
        String termName,
        String sensitivityLevel
    ) {
        static TermSelection empty(String termCode, String termName) {
            return new TermSelection(null, blank(termCode), blank(termName), null);
        }

        private static String blank(String value) {
            return StringUtils.hasText(value) ? value.trim() : null;
        }
    }

    private record DefaultOperatorSpec(String nodeId, String operatorRef, String category) {}

    private record OperatorManifestRow(
        String operatorRef,
        String version,
        String category,
        String manifestJson
    ) {}

    private record ResolvedOperator(
        String operatorRef,
        String version,
        String category,
        String compileTarget,
        JsonNode manifest
    ) {}

    private record LookupJoinSpec(
        String lookupFqn,
        String alias,
        String leftKey,
        String rightKey
    ) {}

    private record DictionaryPair(String from, String to) {}

    private record DbtTestSpec(String name, Map<String, Object> arguments) {}

    private record SourceFreshnessSpec(
        String loadedAtField,
        FreshnessThreshold warnAfter,
        FreshnessThreshold errorAfter
    ) {}

    private record FreshnessThreshold(int count, String period) {}

    private record YamlRaw(String value) {}
}

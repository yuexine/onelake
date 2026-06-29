package com.onelake.common.outbox;

/**
 * Canonical domain event names shared by producers, stream dispatchers, and consumers.
 */
public final class DomainEvents {

    private DomainEvents() {}

    public static final String INTEGRATION_DATASOURCE_CREATED = "integration.datasource.created";
    public static final String INTEGRATION_DATASOURCE_UPDATED = "integration.datasource.updated";
    public static final String INTEGRATION_DATASOURCE_DELETED = "integration.datasource.deleted";
    public static final String INTEGRATION_DATASOURCE_HEALTH_CHANGED = "integration.datasource.health_changed";

    public static final String INTEGRATION_SYNC_TASK_CREATED = "integration.sync_task.created";
    public static final String INTEGRATION_SYNC_TASK_STATUS_CHANGED = "integration.sync_task.status_changed";
    public static final String INTEGRATION_SYNC_RUN_STARTED = "integration.sync_run.started";
    public static final String INTEGRATION_TABLE_LOADED = "integration.table.loaded";
    public static final String INTEGRATION_SYNC_FAILED = "integration.sync.failed";
    public static final String INTEGRATION_SCHEMA_DRIFT = "integration.schema.drift";
    public static final String INTEGRATION_CDC_TASK_CREATED = "integration.cdc_task.created";

    public static final String CATALOG_ASSET_REGISTERED = "catalog.asset.registered";
    public static final String CATALOG_LINEAGE_UPDATED = "catalog.lineage.updated";

    public static final String MODELING_MODEL_PUBLISHED = "modeling.model.published";
    public static final String MODELING_MODEL_LOADED = "modeling.model.loaded";
    public static final String MODELING_MODEL_FAILED = "modeling.model.failed";
    public static final String MODELING_TRANSFORM_COMPLETED = "modeling.transform.completed";
    public static final String MODELING_TERM_CREATED = "modeling.term.created";
    public static final String MODELING_TERM_UPDATED = "modeling.term.updated";
    public static final String MODELING_TERM_APPROVED = "modeling.term.approved";
    public static final String MODELING_TERM_DEPRECATED = "modeling.term.deprecated";
    public static final String MODELING_TERM_BINDING_CHANGED = "modeling.term.binding_changed";
    public static final String MODELING_CODEBOOK_CREATED = "modeling.codebook.created";
    public static final String MODELING_CODEBOOK_UPDATED = "modeling.codebook.updated";
    public static final String MODELING_CODEBOOK_PUBLISHED = "modeling.codebook.published";
    public static final String MODELING_CODEBOOK_DEPRECATED = "modeling.codebook.deprecated";

    public static final String QUALITY_CHECK_COMPLETED = "quality.check.completed";
    public static final String QUALITY_CHECK_FAILED = "quality.check.failed";

    public static final String SECURITY_CLASSIFICATION_ASSIGNED = "security.classification.assigned";
    public static final String SECURITY_PII_DETECTED = "security.pii.detected";
    public static final String SECURITY_MASKING_POLICY_UPDATED = "security.masking_policy.updated";
    public static final String SECURITY_ACCESS_CHANGED = "security.access.changed";

    public static final String DATASERVICE_API_PUBLISHED = "dataservice.api.published";
    public static final String DATASERVICE_API_OFFLINE = "dataservice.api.offline";
    public static final String DATASERVICE_SUBSCRIPTION_APPROVED = "dataservice.subscription.approved";
    public static final String DATASERVICE_SUBSCRIPTION_REVOKED = "dataservice.subscription.revoked";
    public static final String ORCHESTRATION_JOB_BOUND = "orchestration.job.bound";

    /**
     * Pipeline v2 lifecycle events (see docs/流水线模块重设计方案.md §6.3 / §6.5).
     * Replace cross-schema JDBC writes from orchestration → modeling/catalog/quality/security.
     */
    public static final String PIPELINE_PUBLISHED = "pipeline.published";
    public static final String PIPELINE_RUN_SUCCEEDED = "pipeline.run.succeeded";
    public static final String PIPELINE_RUN_FAILED = "pipeline.run.failed";
    public static final String PIPELINE_TASK_LOADED = "pipeline.task.loaded";

    // ============ 数据分析与可视化（analytics）============
    /** 大屏发布：catalog 模块消费，登记大屏为资产下游消费者，回写血缘。 */
    public static final String ANALYTICS_DASHBOARD_PUBLISHED = "analytics.dashboard.published";
    /** Notebook 调度提交：monitor / 全局任务条消费。 */
    public static final String ANALYTICS_NOTEBOOK_RUN_SUBMITTED = "analytics.notebook.run-submitted";
    /** Notebook 状态变更：monitor 消费。 */
    public static final String ANALYTICS_NOTEBOOK_RUN_STATUS_CHANGED = "analytics.notebook.run-status-changed";
    /** Notebook 运行超时（30min 兜底）。 */
    public static final String ANALYTICS_NOTEBOOK_TIMEOUT = "analytics.notebook.timeout";
    /** Notebook 产出新资产（onelake.publish 调用）：catalog 消费，登记新数据集 / 资产。 */
    public static final String ANALYTICS_NOTEBOOK_ARTIFACT_PUBLISHED = "analytics.notebook.artifact-published";
    /** 数据集查询慢查询告警：monitor 消费。 */
    public static final String ANALYTICS_QUERY_SLOW = "analytics.query.slow";
}

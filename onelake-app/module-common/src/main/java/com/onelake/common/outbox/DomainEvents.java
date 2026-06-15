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
    public static final String MODELING_TRANSFORM_COMPLETED = "modeling.transform.completed";

    public static final String QUALITY_CHECK_COMPLETED = "quality.check.completed";
    public static final String QUALITY_CHECK_FAILED = "quality.check.failed";

    public static final String SECURITY_CLASSIFICATION_ASSIGNED = "security.classification.assigned";
    public static final String SECURITY_MASKING_POLICY_UPDATED = "security.masking_policy.updated";
    public static final String SECURITY_ACCESS_CHANGED = "security.access.changed";

    public static final String DATASERVICE_API_PUBLISHED = "dataservice.api.published";
}

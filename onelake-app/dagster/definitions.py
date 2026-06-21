from dagster import Field, String, job, op, repository


@op(
    name="reconcile_sync_task_schedule",
    config_schema={
        "action": Field(String, default_value="UPSERT"),
        "task_id": Field(String, default_value=""),
        "tenant_id": Field(String, default_value=""),
        "name": Field(String, default_value=""),
        "cron": Field(String, default_value=""),
        "airbyte_connection_id": Field(String, default_value=""),
    },
)
def reconcile_sync_task_schedule(context):
    cfg = context.op_config
    context.log.info(
        "OneLake schedule reconcile action=%s task_id=%s tenant_id=%s cron=%s airbyte_connection_id=%s",
        cfg["action"],
        cfg["task_id"],
        cfg["tenant_id"],
        cfg["cron"],
        cfg["airbyte_connection_id"],
    )


@job(name="onelake_sync_task_schedule_reconcile")
def onelake_sync_task_schedule_reconcile():
    reconcile_sync_task_schedule()


@repository(name="onelake")
def onelake_repository():
    return [onelake_sync_task_schedule_reconcile]

import json
import os
import subprocess

from dagster import Bool, Field, Shape, String, job, op, repository


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


@op(
    name="run_dwd_model",
    config_schema={
        "model_name": Field(String),
        "model_id": Field(String),
        "run_id": Field(String),
        "tenant_id": Field(String),
        "trigger_type": Field(String, default_value="MANUAL"),
        "source_fqn": Field(String, default_value=""),
        "target_fqn": Field(String, default_value=""),
        "artifact_path": Field(String, default_value=""),
        "resource_group": Field(String, default_value=""),
        "compute_profile": Field(String, default_value=""),
        "backfill": Field(
            Shape({
                "enabled": Field(Bool, default_value=False),
                "fullRefresh": Field(Bool, default_value=False),
                "partitionStart": Field(String, default_value=""),
                "partitionEnd": Field(String, default_value=""),
                "sourceIntegrationRunId": Field(String, default_value=""),
            }),
            default_value={
                "enabled": False,
                "fullRefresh": False,
                "partitionStart": "",
                "partitionEnd": "",
                "sourceIntegrationRunId": "",
            },
        ),
    },
)
def run_dwd_model(context):
    cfg = context.op_config
    dbt_project_dir = os.getenv("ONELAKE_DBT_PROJECT_DIR", "/opt/onelake/dbt")
    profiles_dir = os.getenv("ONELAKE_DBT_PROFILES_DIR", dbt_project_dir)
    model_name = cfg["model_name"].strip()
    if not model_name:
        raise ValueError("model_name is required")

    cmd = ["dbt", "build", "--select", model_name, "--profiles-dir", profiles_dir]
    backfill = cfg["backfill"]
    if backfill["enabled"] and backfill["fullRefresh"]:
        cmd.append("--full-refresh")
    if backfill["enabled"] and (backfill["partitionStart"] or backfill["partitionEnd"]):
        cmd.extend([
            "--vars",
            json.dumps({
                "backfill_start": backfill["partitionStart"],
                "backfill_end": backfill["partitionEnd"],
                "source_integration_run_id": backfill["sourceIntegrationRunId"],
            }),
        ])
    context.log.info(
        "OneLake DWD model run model=%s model_id=%s run_id=%s tenant_id=%s trigger=%s source=%s target=%s resource=%s/%s backfill=%s",
        model_name,
        cfg["model_id"],
        cfg["run_id"],
        cfg["tenant_id"],
        cfg["trigger_type"],
        cfg["source_fqn"],
        cfg["target_fqn"],
        cfg["resource_group"],
        cfg["compute_profile"],
        backfill,
    )
    result = subprocess.run(
        cmd,
        cwd=dbt_project_dir,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.stdout:
        context.log.info(result.stdout)
    if result.stderr:
        context.log.warning(result.stderr)
    if result.returncode != 0:
        raise RuntimeError(f"dbt build failed for {model_name} with exit code {result.returncode}")
    return {"model_name": model_name, "artifact_path": cfg["artifact_path"]}


@job(name="onelake_sync_task_schedule_reconcile")
def onelake_sync_task_schedule_reconcile():
    reconcile_sync_task_schedule()


@job(name="onelake_dbt_model_run")
def onelake_dbt_model_run():
    run_dwd_model()


@repository(name="onelake")
def onelake_repository():
    return [onelake_sync_task_schedule_reconcile, onelake_dbt_model_run]

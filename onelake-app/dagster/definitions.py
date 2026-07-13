import ast
import os
import json
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

from dagster import Array, AssetMaterialization, Bool, Failure, Field, In, Int, Nothing, Out, Output, Shape, String, graph, job, multiprocess_executor, op, repository


_TASK_OUTPUTS_MARKER = "ONELAKE_OUTPUTS_JSON="
_SPARK_SUBMIT_TASK_TYPES = frozenset({"SPARK_SQL", "PYSPARK"})
_CONTROL_TASK_TYPES = frozenset({"BRANCH", "CONDITION"})
_EXTENSION_TASK_TYPES = frozenset({
    "SUB_PIPELINE", "NOTIFY", "ASSERTION",
})
_OBSERVE_WAIT_TASK_TYPES = frozenset({"SENSOR", "WAIT"})
_OBSERVE_HARD_MAX_WAIT_SECONDS = 24 * 60 * 60
_SENSOR_HARD_MAX_POLL_SECONDS = 5 * 60
_OBSERVE_WAIT_SLICE_SECONDS = 60
_SCRIPT_TASK_TYPES = frozenset({"PYTHON", "SHELL"})
_SCRIPT_DEFAULTS = {
    "timeout_seconds": 60,
    "cpu_seconds": 30,
    "cpu_cores": 1,
    "memory_mb": 256,
    "max_processes": 8,
    "max_files": 256,
    "file_max_bytes": 1024 * 1024,
    "stdout_max_bytes": 256 * 1024,
    "stderr_max_bytes": 256 * 1024,
}
_SCRIPT_HARD_MAX = {
    "timeout_seconds": 900,
    "cpu_seconds": 900,
    "cpu_cores": 4,
    "memory_mb": 2048,
    "max_processes": 64,
    "max_files": 4096,
    "file_max_bytes": 16 * 1024 * 1024,
    "stdout_max_bytes": 1024 * 1024,
    "stderr_max_bytes": 1024 * 1024,
}
_SCRIPT_MAX_BYTES = 64 * 1024
_SCRIPT_RESOURCE_EXIT_CODE = 125
_SCRIPT_RESOURCE_POLL_SECONDS = 0.01
_SCRIPT_MIN_PROCESS_ADDRESS_BYTES = 64 * 1024 * 1024
_TRINO_LOG_DEFAULT_MAX_BYTES = 256 * 1024
_TRINO_LOG_HARD_MAX_BYTES = 1024 * 1024
_TRINO_LOG_CELL_PREVIEW_CHARS = 512
_SAFE_ENV_NAME = re.compile(r"^[A-Z_][A-Z0-9_]{0,63}$")
_FORBIDDEN_ENV_MARKERS = (
    "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "ACCESS_KEY", "PRIVATE_KEY",
    "DAGSTER_", "ONELAKE_INTERNAL", "MINIO_", "AWS_", "DATABASE_", "POSTGRES_",
)
_FORBIDDEN_ENV_NAMES = frozenset({
    "PATH", "HOME", "TMPDIR", "LANG", "PYTHONUNBUFFERED", "PYTHONPATH", "PYTHONHOME",
    "LD_LIBRARY_PATH", "LD_PRELOAD", "BASH_ENV", "ENV",
})

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


# ---------------------------------------------------------------------------
# Spark task op — real execution path for unified pipeline SPARK_SQL / PYSPARK tasks.
# ---------------------------------------------------------------------------


def _default_resource_profile():
    return {
        "executor_memory": "2g",
        "executor_cores": "2",
        "num_executors": "2",
        "driver_memory": "1g",
    }


def _split_spark_sql_statements(sql_text):
    """Split Spark SQL on top-level semicolons without breaking quoted values or comments."""
    statements = []
    current = []
    quote_char = None
    escaped = False
    line_comment = False
    block_comment = False
    index = 0
    text = str(sql_text or "")
    while index < len(text):
        char = text[index]
        next_char = text[index + 1] if index + 1 < len(text) else ""
        if line_comment:
            current.append(char)
            if char in "\r\n":
                line_comment = False
        elif block_comment:
            current.append(char)
            if char == "*" and next_char == "/":
                current.append(next_char)
                index += 1
                block_comment = False
        elif quote_char is not None:
            current.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote_char:
                if next_char == quote_char:
                    current.append(next_char)
                    index += 1
                else:
                    quote_char = None
        elif char in ("'", '"', "`"):
            quote_char = char
            current.append(char)
        elif char == "-" and next_char == "-":
            current.extend((char, next_char))
            index += 1
            line_comment = True
        elif char == "/" and next_char == "*":
            current.extend((char, next_char))
            index += 1
            block_comment = True
        elif char == ";":
            statement = "".join(current).strip()
            if statement:
                statements.append(statement)
            current = []
        else:
            current.append(char)
        index += 1
    statement = "".join(current).strip()
    if statement:
        statements.append(statement)
    return statements


def _build_spark_submit(node, iceberg_catalog, spark_master):
    # 旧单 op 路径和新图执行路径共用同一段 spark-submit 组装，避免 Iceberg/S3/资源参数漂移。
    import tempfile

    task_type = node["task_type"]
    resource = node.get("resource_profile") or _default_resource_profile()
    temp_paths = []
    if task_type in ("PYSPARK", "QUALITY_GATE"):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
            f.write(node["sql_or_script"])
            script_path = f.name
        temp_paths.append(script_path)
        app_args = []
    else:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as sql_file:
            json.dump(_split_spark_sql_statements(node["sql_or_script"]), sql_file)
            sql_path = sql_file.name
        temp_paths.append(sql_path)
        wrapper = f"""
import json
import re
import sys
from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("onelake-spark-sql").getOrCreate()
with open(sys.argv[1], "r", encoding="utf-8") as f:
    statements = json.load(f)
target_fqn = sys.argv[2].strip() if len(sys.argv) > 2 else ""

for statement in statements:
    df = spark.sql(statement)
    if re.match(r"^\\s*(select|show|describe|explain)\\b", statement, re.IGNORECASE):
        df.show(20, truncate=False)

outputs = {{}}
if target_fqn and spark.catalog.tableExists(target_fqn):
    # Iceberg records the writer Spark application and added-records in snapshot summaries.
    # Querying the application-owned snapshots after SQL execution covers INSERT/CTAS/MERGE
    # and row-level DML regardless of comments or leading CTEs, without scanning table data.
    app_id = spark.sparkContext.applicationId.replace("'", "''")
    metrics = spark.sql(
        f"SELECT summary['added-records'] AS added_records "
        f"FROM {{target_fqn}}.snapshots "
        f"WHERE summary['spark.app.id'] = '{{app_id}}'"
    ).collect()
    outputs["rowsWritten"] = sum(int(row["added_records"] or 0) for row in metrics)
if outputs:
    print("{_TASK_OUTPUTS_MARKER}" + json.dumps(outputs, separators=(",", ":")), flush=True)
spark.stop()
"""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
            f.write(wrapper)
            script_path = f.name
        temp_paths.append(script_path)
        app_args = [sql_path, str(node.get("target_fqn") or "")]

    cmd = [
        "spark-submit",
        "--master", spark_master,
        "--executor-memory", resource["executor_memory"],
        "--executor-cores", resource["executor_cores"],
        "--num-executors", resource["num_executors"],
        "--driver-memory", resource["driver_memory"],
        "--packages",
        "org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2,"
        "org.apache.iceberg:iceberg-aws-bundle:1.5.2,"
        "org.apache.hadoop:hadoop-aws:3.3.4,"
        "org.postgresql:postgresql:42.7.3,"
        "com.mysql:mysql-connector-j:8.4.0",
        "--conf", "spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
        "--conf", f"spark.sql.defaultCatalog={iceberg_catalog}",
        "--conf", "spark.sql.catalog.onelake=org.apache.iceberg.spark.SparkCatalog",
        "--conf", "spark.sql.catalog.onelake.type=hive",
        "--conf", "spark.sql.catalog.onelake.uri=thrift://hive-metastore:9083",
        "--conf", "spark.sql.catalog.onelake.warehouse=s3a://onelake/warehouse",
        "--conf", "spark.sql.catalog.onelake.io-impl=org.apache.iceberg.aws.s3.S3FileIO",
        "--conf", "spark.sql.catalog.onelake.s3.endpoint=http://minio:9000",
        "--conf", "spark.sql.catalog.onelake.s3.path-style-access=true",
        "--conf", "spark.sql.catalog.onelake.s3.access-key-id=minio",
        "--conf", "spark.sql.catalog.onelake.s3.secret-access-key=minio12345",
        "--conf", "spark.sql.catalog.onelake.client.region=us-east-1",
        "--conf", "spark.hadoop.fs.s3a.endpoint=http://minio:9000",
        "--conf", "spark.hadoop.fs.s3a.path.style.access=true",
        "--conf", "spark.hadoop.fs.s3a.connection.ssl.enabled=false",
        "--conf", "spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem",
        "--conf", "spark.executorEnv.AWS_ACCESS_KEY_ID=minio",
        "--conf", "spark.executorEnv.AWS_SECRET_ACCESS_KEY=minio12345",
        "--conf", "spark.yarn.appMasterEnv.AWS_ACCESS_KEY_ID=minio",
        "--conf", "spark.yarn.appMasterEnv.AWS_SECRET_ACCESS_KEY=minio12345",
        script_path,
    ]
    cmd.extend(app_args)
    return cmd, temp_paths


def _dispatch_graph_node_command(node, iceberg_catalog, spark_master):
    """Resolve a graph node to its M4 execution adapter without weakening old behavior."""
    task_type = node["task_type"]
    if task_type in _SPARK_SUBMIT_TASK_TYPES:
        return _build_spark_submit(node, iceberg_catalog, spark_master)
    if task_type == "QUALITY_GATE":
        # QUALITY_GATE was Spark-backed before M4 and remains so for rollback compatibility.
        return _build_spark_submit(node, iceberg_catalog, spark_master)
    if task_type in _EXTENSION_TASK_TYPES:
        raise NotImplementedError(
            f"{task_type} is executed by the graph control/observe adapter"
        )
    raise ValueError(f"unsupported pipeline task_type: {task_type}")


def _sensor_asset_ready(base_url, tenant_id, asset_fqn, partition,
                        request_timeout_seconds=5):
    import requests

    target_base_url = (base_url or os.getenv("ONELAKE_CALLBACK_BASE_URL", "")).rstrip("/")
    if not target_base_url:
        raise RuntimeError("SENSOR callback base URL is empty")
    token = os.getenv("ONELAKE_INTERNAL_TOKEN", "") or os.getenv(
        "ONELAKE_ORCHESTRATION_INTERNAL_TOKEN", ""
    )
    headers = {}
    if token:
        headers["X-Onelake-Internal-Token"] = token
    response = requests.get(
        target_base_url + "/api/v1/internal/orchestration/dagster/asset-readiness",
        params={
            "tenantId": tenant_id,
            "assetFqn": asset_fqn,
            **({"partition": partition} if partition else {}),
        },
        headers=headers,
        timeout=max(0.001, min(5.0, float(request_timeout_seconds))),
    )
    response.raise_for_status()
    body = response.json()
    readiness = body.get("data") or {}
    return bool(readiness.get("ready")), readiness


def _bounded_observe_seconds(node, field, minimum, maximum):
    value = node.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{field} must be an integer")
    if value < minimum or value > maximum:
        raise ValueError(f"{field} must be between {minimum} and {maximum}")
    return value


def _wait_observe_interval(cancellation_event, seconds):
    if seconds <= 0:
        return
    if cancellation_event.wait(seconds):
        raise KeyboardInterrupt("observe node cancelled")


def _parse_logical_date(value):
    text = str(value or "").strip()
    if not text:
        raise ValueError("WAIT offset mode requires runtime logical_date")
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _execute_observe_wait(node, runtime_params, base_url, tenant_id,
                          cancellation_event, log):
    task_type = node.get("task_type")
    if task_type == "SENSOR":
        asset_fqn = str(node.get("sensor_asset_fqn") or "").strip()
        partition = str(node.get("sensor_partition") or "").strip()
        if not asset_fqn:
            raise ValueError("SENSOR sensor_asset_fqn must not be empty")
        timeout_seconds = _bounded_observe_seconds(
            node, "timeout_seconds", 1, _OBSERVE_HARD_MAX_WAIT_SECONDS,
        )
        poll_seconds = _bounded_observe_seconds(
            node, "poll_interval_seconds", 1, _SENSOR_HARD_MAX_POLL_SECONDS,
        )
        if poll_seconds > timeout_seconds:
            raise ValueError("SENSOR poll_interval_seconds must not exceed timeout_seconds")
        on_timeout = str(node.get("on_timeout") or "").strip().upper()
        if on_timeout not in {"FAILED", "SKIPPED"}:
            raise ValueError("SENSOR on_timeout must be FAILED or SKIPPED")

        deadline = time.monotonic() + timeout_seconds
        poll_count = 0
        last_error = None
        while True:
            if cancellation_event.is_set():
                raise KeyboardInterrupt("SENSOR cancelled")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            poll_count += 1
            try:
                ready, readiness = _sensor_asset_ready(
                    base_url, tenant_id, asset_fqn, partition,
                    min(5.0, remaining),
                )
                last_error = None
                if ready:
                    source = readiness.get("source") or "asset_readiness"
                    return {
                        "status": "SUCCEEDED",
                        "message": (
                            f"SENSOR ready asset={asset_fqn} "
                            f"partition={partition or '<latest>'} source={source} "
                            f"polls={poll_count}"
                        ),
                    }
            except Exception as exc:
                last_error = str(exc)
                log.warning(
                    "SENSOR readiness poll failed asset=%s partition=%s poll=%s error=%s",
                    asset_fqn, partition or "<latest>", poll_count, exc,
                )

            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            _wait_observe_interval(cancellation_event, min(poll_seconds, remaining))

        message = (
            f"SENSOR timed out after {timeout_seconds}s asset={asset_fqn} "
            f"partition={partition or '<latest>'} polls={poll_count}"
        )
        if last_error:
            message += f" last_error={last_error}"
        return {"status": on_timeout, "message": message}

    if task_type == "WAIT":
        offset_seconds = node.get("wait_offset_seconds", -1)
        duration_seconds = node.get("wait_duration_seconds", -1)
        if isinstance(offset_seconds, bool) or not isinstance(offset_seconds, int):
            raise ValueError("WAIT wait_offset_seconds must be an integer")
        if isinstance(duration_seconds, bool) or not isinstance(duration_seconds, int):
            raise ValueError("WAIT wait_duration_seconds must be an integer")
        has_offset = offset_seconds >= 0
        has_duration = duration_seconds >= 0
        if has_offset == has_duration:
            raise ValueError("WAIT requires exactly one offset or duration")

        started_monotonic = time.monotonic()
        if has_duration:
            if duration_seconds < 1 or duration_seconds > _OBSERVE_HARD_MAX_WAIT_SECONDS:
                raise ValueError(
                    f"WAIT duration must be between 1 and {_OBSERVE_HARD_MAX_WAIT_SECONDS} seconds"
                )
            target_monotonic = started_monotonic + duration_seconds
            target_description = f"duration={duration_seconds}s"
        else:
            if offset_seconds > _OBSERVE_HARD_MAX_WAIT_SECONDS:
                raise ValueError(
                    f"WAIT offset must not exceed {_OBSERVE_HARD_MAX_WAIT_SECONDS} seconds"
                )
            logical_date = _parse_logical_date((runtime_params or {}).get("logical_date"))
            target = logical_date + timedelta(seconds=offset_seconds)
            remaining_wall = max(0.0, target.timestamp() - time.time())
            if remaining_wall > _OBSERVE_HARD_MAX_WAIT_SECONDS:
                raise ValueError(
                    "WAIT logical_date target exceeds the 86400 second runtime hard limit"
                )
            target_monotonic = started_monotonic + remaining_wall
            target_description = f"target={target.isoformat()}"

        while True:
            if cancellation_event.is_set():
                raise KeyboardInterrupt("WAIT cancelled")
            remaining = target_monotonic - time.monotonic()
            if remaining <= 0:
                return {
                    "status": "SUCCEEDED",
                    "message": f"WAIT reached {target_description}",
                }
            _wait_observe_interval(
                cancellation_event, min(remaining, _OBSERVE_WAIT_SLICE_SECONDS),
            )

    raise ValueError(f"unsupported observe task_type: {task_type}")


def _run_observe_wait_with_callback(node, runtime_params, base_url, run_id,
                                    tenant_id, task_key, attempt,
                                    cancellation_event, log):
    try:
        outcome = _execute_observe_wait(
            node, runtime_params, base_url, tenant_id, cancellation_event, log,
        )
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as exc:
        outcome = {"status": "FAILED", "message": str(exc)}

    message = outcome["message"]
    log_ref = _upload_log(tenant_id, run_id, task_key, attempt, message, log)
    payload = {
        "status": outcome["status"],
        "finishedAt": _now(),
        "dagsterStepKey": task_key,
        "attempt": attempt,
        "logRef": log_ref,
    }
    if outcome["status"] == "FAILED":
        payload["errorMsg"] = _tail(message, 3900)
    _callback(base_url, run_id, task_key, payload, log)
    return outcome["status"], message


def _internal_api_headers():
    token = os.getenv("ONELAKE_INTERNAL_TOKEN", "") or os.getenv(
        "ONELAKE_ORCHESTRATION_INTERNAL_TOKEN", ""
    )
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Onelake-Internal-Token"] = token
    return headers


def _internal_api_base_url(base_url, task_type):
    target = (base_url or os.getenv("ONELAKE_CALLBACK_BASE_URL", "")).rstrip("/")
    if not target:
        raise RuntimeError(f"{task_type} internal API base URL is empty")
    return target


def _internal_response_data(response, operation):
    try:
        body = response.json()
    except ValueError:
        body = {}
    if not response.ok:
        message = body.get("message") or response.text or response.reason
        raise RuntimeError(f"{operation} failed: {message}")
    data = body.get("data")
    if not isinstance(data, dict):
        raise RuntimeError(f"{operation} returned invalid data")
    return data


def _trigger_sub_pipeline(base_url, run_id, task_key, sub_dag_id, attempt):
    import requests

    response = requests.post(
        _internal_api_base_url(base_url, "SUB_PIPELINE")
        + "/api/v1/internal/orchestration/dagster/sub-pipelines/trigger",
        json={
            "parentRunId": run_id,
            "taskKey": task_key,
            "subDagId": sub_dag_id,
            "attempt": attempt,
        },
        headers=_internal_api_headers(),
        timeout=10,
    )
    return _internal_response_data(response, "SUB_PIPELINE trigger")


def _sub_pipeline_status(base_url, parent_run_id, child_run_id):
    import requests

    response = requests.get(
        _internal_api_base_url(base_url, "SUB_PIPELINE")
        + f"/api/v1/internal/orchestration/dagster/sub-pipelines/runs/{quote(str(child_run_id), safe='')}",
        params={"parentRunId": parent_run_id},
        headers=_internal_api_headers(),
        timeout=5,
    )
    return _internal_response_data(response, "SUB_PIPELINE status")


def _send_pipeline_notification(base_url, run_id, task_key, node):
    import requests

    receiver_id = str(node.get("notification_receiver_id") or "").strip()
    response = requests.post(
        _internal_api_base_url(base_url, "NOTIFY")
        + "/api/v1/internal/orchestration/dagster/notifications",
        json={
            "parentRunId": run_id,
            "taskKey": task_key,
            **({"receiverId": receiver_id} if receiver_id else {}),
            "title": str(node.get("notification_title") or ""),
            "message": str(node.get("notification_message") or ""),
            "link": str(node.get("notification_link") or ""),
            "level": str(node.get("notification_level") or "INFO"),
        },
        headers=_internal_api_headers(),
        timeout=10,
    )
    return _internal_response_data(response, "NOTIFY send")


def _execute_extension_node(node, base_url, run_id, task_key, attempt,
                            cancellation_event, log):
    task_type = node.get("task_type")
    if task_type == "ASSERTION":
        result = _safe_control_eval(node.get("expression"))
        if not isinstance(result, bool):
            raise ValueError("ASSERTION expression must resolve to boolean")
        if not result:
            raise AssertionError("ASSERTION expression evaluated to false")
        return {
            "message": "ASSERTION passed",
            "outputs": {"assertionResult": True},
        }

    if task_type == "NOTIFY":
        result = _send_pipeline_notification(base_url, run_id, task_key, node)
        return {
            "message": "NOTIFY delivered" if result.get("created") else "NOTIFY already delivered",
            "outputs": {"notificationCreated": bool(result.get("created"))},
        }

    if task_type == "SUB_PIPELINE":
        sub_dag_id = str(node.get("sub_dag_id") or "").strip()
        if not sub_dag_id:
            raise ValueError("SUB_PIPELINE sub_dag_id must not be empty")
        child = _trigger_sub_pipeline(
            base_url, run_id, task_key, sub_dag_id, attempt,
        )
        child_run_id = str(child.get("runId") or "").strip()
        if not child_run_id:
            raise RuntimeError("SUB_PIPELINE trigger returned empty runId")
        outputs = {"subPipelineRunId": child_run_id, "subDagId": sub_dag_id}
        if not bool(node.get("wait_for_completion")):
            return {
                "message": f"SUB_PIPELINE accepted child_run_id={child_run_id}",
                "outputs": outputs,
            }

        timeout_seconds = _bounded_observe_seconds(
            node, "sub_timeout_seconds", 1, _OBSERVE_HARD_MAX_WAIT_SECONDS,
        )
        poll_seconds = _bounded_observe_seconds(
            node, "sub_poll_interval_seconds", 1, _SENSOR_HARD_MAX_POLL_SECONDS,
        )
        if poll_seconds > timeout_seconds:
            raise ValueError(
                "SUB_PIPELINE sub_poll_interval_seconds must not exceed sub_timeout_seconds"
            )
        deadline = time.monotonic() + timeout_seconds
        status = str(child.get("status") or "QUEUED").upper()
        while status not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(
                    f"SUB_PIPELINE child run {child_run_id} timed out after {timeout_seconds}s"
                )
            _wait_observe_interval(cancellation_event, min(poll_seconds, remaining))
            child = _sub_pipeline_status(base_url, run_id, child_run_id)
            status = str(child.get("status") or "").upper()
        outputs["subPipelineStatus"] = status
        if status != "SUCCEEDED":
            raise RuntimeError(f"SUB_PIPELINE child run {child_run_id} finished with {status}")
        return {
            "message": f"SUB_PIPELINE child run {child_run_id} succeeded",
            "outputs": outputs,
        }

    raise ValueError(f"unsupported extension task_type: {task_type}")


def _run_extension_with_callback(node, base_url, run_id, tenant_id, task_key,
                                 attempt, cancellation_event, log):
    try:
        outcome = _execute_extension_node(
            node, base_url, run_id, task_key, attempt, cancellation_event, log,
        )
        status = "SUCCEEDED"
        message = outcome["message"]
        outputs = outcome.get("outputs") or {}
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as exc:
        status = "FAILED"
        message = str(exc)
        outputs = {}

    log_ref = _upload_log(tenant_id, run_id, task_key, attempt, message, log)
    payload = {
        "status": status,
        "finishedAt": _now(),
        "dagsterStepKey": task_key,
        "attempt": attempt,
        "logRef": log_ref,
    }
    if status == "SUCCEEDED":
        payload["outputs"] = outputs
    else:
        payload["errorMsg"] = _tail(message, 3900)
    _callback(base_url, run_id, task_key, payload, log)
    return status, message


def _safe_control_eval(expression):
    """Evaluate a small, data-only expression language without executing code."""
    source = str(expression or "").strip()
    if not source:
        raise ValueError("control expression must not be empty")
    if len(source) > 4096:
        raise ValueError("control expression exceeds 4096 characters")
    try:
        parsed = ast.parse(source, mode="eval")
    except SyntaxError as exc:
        raise ValueError(f"control expression syntax is invalid: {exc.msg}") from exc
    if sum(1 for _ in ast.walk(parsed)) > 64:
        raise ValueError("control expression is too complex")

    def evaluate(node):
        if isinstance(node, ast.Expression):
            return evaluate(node.body)
        if isinstance(node, ast.Constant) and isinstance(
                node.value, (str, int, float, bool, type(None))):
            return node.value
        if isinstance(node, ast.Name) and node.id.lower() in {"true", "false", "null"}:
            return {"true": True, "false": False, "null": None}[node.id.lower()]
        if isinstance(node, (ast.List, ast.Tuple, ast.Set)):
            values = [evaluate(item) for item in node.elts]
            return set(values) if isinstance(node, ast.Set) else values
        if isinstance(node, ast.BoolOp) and isinstance(node.op, (ast.And, ast.Or)):
            values = [evaluate(value) for value in node.values]
            if not all(isinstance(value, bool) for value in values):
                raise ValueError("boolean operators require boolean operands")
            return all(values) if isinstance(node.op, ast.And) else any(values)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            value = evaluate(node.operand)
            if not isinstance(value, bool):
                raise ValueError("not requires a boolean operand")
            return not value
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            value = evaluate(node.operand)
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError("unary +/- requires a number")
            return value if isinstance(node.op, ast.UAdd) else -value
        if isinstance(node, ast.BinOp) and isinstance(
                node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.Mod)):
            left, right = evaluate(node.left), evaluate(node.right)
            if (isinstance(left, bool) or isinstance(right, bool)
                    or not isinstance(left, (int, float))
                    or not isinstance(right, (int, float))):
                raise ValueError("arithmetic operators require numbers")
            operations = {
                ast.Add: lambda: left + right,
                ast.Sub: lambda: left - right,
                ast.Mult: lambda: left * right,
                ast.Div: lambda: left / right,
                ast.Mod: lambda: left % right,
            }
            return operations[type(node.op)]()
        if isinstance(node, ast.Compare):
            left = evaluate(node.left)
            for operator, comparator in zip(node.ops, node.comparators):
                right = evaluate(comparator)
                comparisons = {
                    ast.Eq: lambda: left == right,
                    ast.NotEq: lambda: left != right,
                    ast.Lt: lambda: left < right,
                    ast.LtE: lambda: left <= right,
                    ast.Gt: lambda: left > right,
                    ast.GtE: lambda: left >= right,
                    ast.In: lambda: left in right,
                    ast.NotIn: lambda: left not in right,
                }
                action = comparisons.get(type(operator))
                if action is None:
                    raise ValueError("comparison operator is not allowed")
                try:
                    matched = action()
                except (TypeError, ValueError, ZeroDivisionError) as exc:
                    raise ValueError(f"control comparison is invalid: {exc}") from exc
                if not matched:
                    return False
                left = right
            return True
        raise ValueError(f"control expression element is not allowed: {type(node).__name__}")

    try:
        return evaluate(parsed)
    except ZeroDivisionError as exc:
        raise ValueError("control expression divides by zero") from exc


def _branch_mapping(node):
    raw = node.get("branches") or []
    if isinstance(raw, dict):
        raw = [
            {"value": value, "targets": targets if isinstance(targets, list) else [targets]}
            for value, targets in raw.items()
        ]
    mapping = {}
    for entry in raw:
        value = str(entry.get("value") or "").strip()
        targets = [str(target) for target in entry.get("targets") or []]
        if not value or not targets:
            raise ValueError("BRANCH branches require non-empty value and targets")
        mapping[value] = targets
    if not mapping:
        raise ValueError("BRANCH branches must not be empty")
    return mapping


def _branch_selector(expression):
    source = str(expression or "").strip()
    try:
        value = _safe_control_eval(source)
    except ValueError:
        # Parameter rendering commonly turns ${env} into an unquoted token such as prod.
        # Treat only a single inert token as a string; calls, attributes and operators fail.
        if not re.fullmatch(r"[A-Za-z0-9_.:-]+", source):
            raise
        value = source
    if isinstance(value, (list, tuple, set, dict)) or value is None:
        raise ValueError("BRANCH expression must resolve to a scalar value")
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def _control_targets(node, downstream_keys):
    task_type = node.get("task_type")
    children = set(downstream_keys or [])
    if task_type == "CONDITION":
        result = _safe_control_eval(node.get("expression"))
        if not isinstance(result, bool):
            raise ValueError("CONDITION expression must resolve to boolean")
        return (children if result else set()), {"conditionResult": result}
    if task_type == "BRANCH":
        selector = _branch_selector(node.get("expression"))
        mapping = _branch_mapping(node)
        if selector not in mapping:
            raise ValueError(f"BRANCH expression selected unmapped value: {selector}")
        selected = set(mapping[selector])
        unknown = selected - children
        if unknown:
            raise ValueError(
                "BRANCH selected targets are not direct downstream tasks: "
                + ", ".join(sorted(unknown))
            )
        return selected, {"selectedBranch": selector, "selectedTargets": sorted(selected)}
    raise ValueError(f"unsupported control task_type: {task_type}")


def _bounded_int(node, key):
    value = int(node.get(key, _SCRIPT_DEFAULTS[key]) or _SCRIPT_DEFAULTS[key])
    if value < 1 or value > _SCRIPT_HARD_MAX[key]:
        raise ValueError(
            f"script sandbox {key} must be between 1 and {_SCRIPT_HARD_MAX[key]}"
        )
    return value


def _effective_script_limits(limits):
    effective = dict(limits)
    memory_bytes = limits["memory_mb"] * 1024 * 1024
    memory_slots = max(1, memory_bytes // _SCRIPT_MIN_PROCESS_ADDRESS_BYTES)
    # RLIMIT_AS/RLIMIT_CPU are per-process. Divide the node budget across a
    # conservatively reduced process ceiling so the kernel-enforced hard limits
    # cannot multiply beyond the configured node total. The /proc monitor below
    # remains useful for diagnostics and early aggregate termination.
    effective_processes = max(1, min(
        limits["max_processes"],
        memory_slots,
        limits["cpu_seconds"],
    ))
    effective["effective_max_processes"] = effective_processes
    effective["per_process_memory_bytes"] = memory_bytes // effective_processes
    effective["per_process_cpu_seconds"] = max(
        1, limits["cpu_seconds"] // effective_processes,
    )
    return effective


def _script_environment(node):
    safe = {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "HOME": "/workspace",
        "TMPDIR": "/tmp",
        "LANG": "C.UTF-8",
        "PYTHONUNBUFFERED": "1",
        "LD_LIBRARY_PATH": "/usr/local/lib",
    }
    for item in node.get("env") or []:
        key = str(item.get("key") or "").strip().upper()
        value = "" if item.get("value") is None else str(item.get("value"))
        if not _SAFE_ENV_NAME.fullmatch(key):
            raise ValueError(f"script sandbox environment key is invalid: {key}")
        if key in _FORBIDDEN_ENV_NAMES or any(
                marker in key for marker in _FORBIDDEN_ENV_MARKERS):
            raise ValueError(f"script sandbox environment key is reserved: {key}")
        if len(value.encode("utf-8")) > 4096:
            raise ValueError(f"script sandbox environment value is too large: {key}")
        safe[key] = value
    return safe


def _script_preexec(limits):
    def apply_limits():
        # Namespace creation needs one trusted Bubblewrap process before script
        # rlimits apply. CPU affinity is safe to constrain on the launcher itself;
        # prlimit below applies the remaining limits immediately before the script.
        if hasattr(os, "sched_getaffinity") and hasattr(os, "sched_setaffinity"):
            allowed = sorted(os.sched_getaffinity(0))
            os.sched_setaffinity(0, set(allowed[:limits["cpu_cores"]]))

    return apply_limits


def _script_sandbox_command(
        task_type, sandbox_binary, host_workdir, host_tmpdir, script_name, env, limits):
    # Only the language runtime is visible read-only. The source tree, /etc, /proc,
    # control-plane config and host filesystem are deliberately absent. --unshare-all
    # creates a fresh network namespace with no interfaces, so networking is denied.
    command = [
        sandbox_binary,
        "--die-with-parent",
        "--unshare-all",
        "--cap-drop", "ALL",
        "--new-session",
        "--clearenv",
        "--ro-bind", "/usr", "/usr",
        "--symlink", "usr/bin", "/bin",
        "--symlink", "usr/lib", "/lib",
        "--symlink", "usr/lib64", "/lib64",
        "--dev", "/dev",
        # /tmp uses a separate host directory owned by this execution. The outer
        # monitor scans it together with /workspace, so temporary files cannot
        # bypass the node byte/entry budget.
        "--bind", host_tmpdir, "/tmp",
        "--bind", host_workdir, "/workspace",
        "--chdir", "/workspace",
    ]
    for key, value in env.items():
        command.extend(["--setenv", key, value])
    interpreter = "/usr/local/bin/python3" if task_type == "PYTHON" else "/usr/bin/dash"
    command.extend([
        "--", "/usr/bin/prlimit",
        f"--cpu={limits['per_process_cpu_seconds']}:{limits['per_process_cpu_seconds']}",
        f"--as={limits['per_process_memory_bytes']}:{limits['per_process_memory_bytes']}",
        f"--nproc={limits['effective_max_processes']}:{limits['effective_max_processes']}",
        f"--fsize={limits['file_max_bytes']}:{limits['file_max_bytes']}",
        "--nofile=64:64",
        "--", interpreter, f"/workspace/{script_name}",
    ])
    return command


def _capture_pipe(stream, max_bytes, output):
    total = 0
    kept = bytearray()
    while True:
        chunk = stream.read(64 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if len(kept) < max_bytes:
            kept.extend(chunk[:max_bytes - len(kept)])
    output["value"], decode_truncated = _finish_bounded_capture(
        kept, total, max_bytes,
    )
    output["truncated"] = total > max_bytes or decode_truncated


def _finish_bounded_capture(kept, total, max_bytes):
    def decode_bounded(data, budget):
        decoded = bytes(data).decode("utf-8", errors="replace")
        encoded = decoded.encode("utf-8")
        if len(encoded) <= budget:
            return decoded, False
        return encoded[:budget].decode("utf-8", errors="ignore"), True

    if total <= max_bytes:
        return decode_bounded(kept, max_bytes)
    marker = f"\n... [sandbox output truncated: total_bytes={total} max_bytes={max_bytes}] ...\n".encode()
    if len(marker) >= max_bytes:
        return marker[:max_bytes].decode("utf-8", errors="ignore"), True
    body, _ = decode_bounded(kept, max_bytes - len(marker))
    return body + marker.decode("utf-8"), True


def _script_resource_accounting_available():
    return os.name == "posix" and os.path.isdir("/proc")


def _read_process_stat(pid):
    try:
        with open(f"/proc/{pid}/stat", "r", encoding="utf-8") as stat_file:
            raw = stat_file.read()
    except (FileNotFoundError, PermissionError, ProcessLookupError, OSError):
        return None
    closing = raw.rfind(")")
    if closing < 0:
        return None
    opening = raw.find("(")
    if opening < 0 or opening >= closing:
        return None
    fields = raw[closing + 2:].split()
    if len(fields) < 22:
        return None
    try:
        return {
            "comm": raw[opening + 1:closing],
            "ppid": int(fields[1]),
            "utime": int(fields[11]),
            "stime": int(fields[12]),
            "cutime": int(fields[13]),
            "cstime": int(fields[14]),
            "rss_pages": int(fields[21]),
        }
    except (TypeError, ValueError):
        return None


def _script_process_tree_usage(root_pid):
    stats = {}
    try:
        proc_entries = os.listdir("/proc")
    except OSError:
        return None
    for entry in proc_entries:
        if not entry.isdigit():
            continue
        stat = _read_process_stat(int(entry))
        if stat is not None:
            stats[int(entry)] = stat
    if root_pid not in stats:
        return None

    descendants = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, stat in stats.items():
            if pid not in descendants and stat["ppid"] in descendants:
                descendants.add(pid)
                changed = True

    ticks = max(1, int(os.sysconf("SC_CLK_TCK")))
    page_size = max(1, int(os.sysconf("SC_PAGE_SIZE")))
    cpu_ticks = 0
    rss_pages = 0
    for pid in descendants:
        stat = stats.get(pid)
        if stat is None:
            continue
        cpu_ticks += stat["utime"] + stat["stime"]
        rss_pages += max(0, stat["rss_pages"])
    # Bubblewrap is the PID-namespace reaper. Its waited-for child CPU time
    # remains in cutime/cstime after short-lived descendants disappear.
    root_stat = stats.get(root_pid)
    if root_stat is not None:
        cpu_ticks += max(0, root_stat["cutime"]) + max(0, root_stat["cstime"])
    # Popen starts Bubblewrap, which keeps a trusted namespace init/reaper
    # alongside the actual script. prlimit can also be observed briefly before
    # it execs the interpreter. Neither belongs to the tenant process budget;
    # RLIMIT_NPROC is still applied by prlimit to the script and its children.
    script_processes = sum(
        1 for pid in descendants
        if pid != root_pid and stats[pid]["comm"] not in {"bwrap", "prlimit"}
    )
    return {
        "cpu_seconds": cpu_ticks / ticks,
        "memory_bytes": rss_pages * page_size,
        "processes": script_processes,
    }


def _script_workspace_usage(workdirs, excluded_path, limits):
    total = 0
    entries = 0
    excluded = os.path.realpath(excluded_path) if excluded_path else None
    if isinstance(workdirs, (str, bytes, os.PathLike)):
        pending = [workdirs]
    else:
        pending = list(workdirs)
    while pending:
        root = pending.pop()
        try:
            children = os.scandir(root)
        except (FileNotFoundError, PermissionError, OSError):
            continue
        with children:
            for entry in children:
                path = entry.path
                if excluded is not None and os.path.realpath(path) == excluded:
                    continue
                entries += 1
                if entries > limits["max_files"]:
                    return {"bytes": total, "entries": entries}
                try:
                    if entry.is_symlink():
                        continue
                    if entry.is_dir(follow_symlinks=False):
                        pending.append(path)
                        continue
                    total += entry.stat(follow_symlinks=False).st_size
                    if total > limits["file_max_bytes"]:
                        return {"bytes": total, "entries": entries}
                except (FileNotFoundError, PermissionError, OSError):
                    continue
    return {"bytes": total, "entries": entries}


def _script_resource_violation(process, workdirs, script_path, limits):
    usage = _script_process_tree_usage(process.pid)
    if usage is None:
        if process.poll() is not None:
            return None
        return "resource accounting unavailable"
    memory_limit = limits["memory_mb"] * 1024 * 1024
    if usage["memory_bytes"] > memory_limit:
        return (
            f"memory exceeded {memory_limit} bytes "
            f"(observed {usage['memory_bytes']} bytes across process tree)"
        )
    if usage["cpu_seconds"] > limits["cpu_seconds"]:
        return (
            f"cpu exceeded {limits['cpu_seconds']} seconds "
            f"(observed {usage['cpu_seconds']:.3f} seconds across process tree)"
        )
    if usage["processes"] > limits["effective_max_processes"]:
        return (
            f"process count exceeded {limits['effective_max_processes']} "
            f"(observed {usage['processes']})"
        )
    workspace = _script_workspace_usage(workdirs, script_path, limits)
    if workspace["entries"] > limits["max_files"]:
        return (
            f"workspace entries exceeded {limits['max_files']} "
            f"(observed at least {workspace['entries']})"
        )
    if workspace["bytes"] > limits["file_max_bytes"]:
        return (
            f"workspace output exceeded {limits['file_max_bytes']} bytes "
            f"(observed at least {workspace['bytes']} bytes)"
        )
    return None


def _monitor_script_resources(
        process, workdirs, script_path, limits, stop_event, violation_output):
    while not stop_event.wait(_SCRIPT_RESOURCE_POLL_SECONDS):
        if process.poll() is not None:
            return
        violation = _script_resource_violation(
            process, workdirs, script_path, limits,
        )
        if violation is not None:
            violation_output["reason"] = violation
            _terminate_script_process(process)
            return


def _terminate_script_process(process):
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=2)
    except (ProcessLookupError, ChildProcessError):
        return
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=2)
        except (subprocess.TimeoutExpired, ChildProcessError):
            pass


def _execute_sandboxed_script(node, register_process=None, unregister_process=None):
    task_type = str(node.get("task_type") or "").upper()
    if task_type not in _SCRIPT_TASK_TYPES:
        raise ValueError(f"unsupported script task_type: {task_type}")
    script = str(node.get("sql_or_script") or "")
    script_size = len(script.encode("utf-8"))
    if not script.strip():
        raise ValueError(f"{task_type} script must not be empty")
    if script_size > _SCRIPT_MAX_BYTES:
        raise ValueError(
            f"{task_type} script exceeds runtime limit {_SCRIPT_MAX_BYTES} bytes"
        )
    if node.get("network_allowlist"):
        raise ValueError("script sandbox network allowlist is not enabled; default-deny only")

    limits = {key: _bounded_int(node, key) for key in _SCRIPT_DEFAULTS}
    limits["cpu_seconds"] = min(limits["cpu_seconds"], limits["timeout_seconds"])
    limits = _effective_script_limits(limits)
    env = _script_environment(node)
    sandbox_binary = shutil.which("bwrap")
    if not sandbox_binary:
        raise RuntimeError("script sandbox unavailable: bubblewrap executable not found")
    if not _script_resource_accounting_available():
        raise RuntimeError(
            "script sandbox unavailable: Linux /proc resource accounting is required"
        )

    suffix = ".py" if task_type == "PYTHON" else ".sh"
    process = None
    timed_out = False
    resource_violation = {}
    with tempfile.TemporaryDirectory(prefix="onelake-script-") as sandbox_root:
        workdir = os.path.join(sandbox_root, "workspace")
        tmpdir = os.path.join(sandbox_root, "tmp")
        os.mkdir(workdir, 0o700)
        os.mkdir(tmpdir, 0o700)
        writable_roots = (workdir, tmpdir)
        script_name = "main" + suffix
        script_path = os.path.join(workdir, script_name)
        with open(script_path, "w", encoding="utf-8") as script_file:
            script_file.write(script)
        os.chmod(script_path, 0o400)
        cmd = _script_sandbox_command(
            task_type, sandbox_binary, workdir, tmpdir, script_name, env, limits,
        )
        process = subprocess.Popen(
            cmd,
            cwd=workdir,
            env={"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8"},
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
            preexec_fn=_script_preexec(limits),
        )
        if register_process is not None:
            register_process(process)
        stdout_capture = {}
        stderr_capture = {}
        readers = [
            threading.Thread(
                target=_capture_pipe,
                args=(process.stdout, limits["stdout_max_bytes"], stdout_capture),
                daemon=True,
            ),
            threading.Thread(
                target=_capture_pipe,
                args=(process.stderr, limits["stderr_max_bytes"], stderr_capture),
                daemon=True,
            ),
        ]
        for reader in readers:
            reader.start()
        monitor_stop = threading.Event()
        resource_monitor = threading.Thread(
            target=_monitor_script_resources,
            args=(
                process, writable_roots, script_path, limits,
                monitor_stop, resource_violation,
            ),
            daemon=True,
        )
        resource_monitor.start()
        try:
            process.wait(timeout=limits["timeout_seconds"])
        except subprocess.TimeoutExpired:
            timed_out = True
            _terminate_script_process(process)
        finally:
            monitor_stop.set()
            resource_monitor.join(timeout=3)
            for reader in readers:
                reader.join(timeout=3)
            if unregister_process is not None:
                unregister_process(process)
        if not resource_violation:
            final_workspace = _script_workspace_usage(
                writable_roots, script_path, limits,
            )
            if final_workspace["entries"] > limits["max_files"]:
                resource_violation["reason"] = (
                    f"workspace entries exceeded {limits['max_files']} "
                    f"(observed at least {final_workspace['entries']})"
                )
            elif final_workspace["bytes"] > limits["file_max_bytes"]:
                resource_violation["reason"] = (
                    f"workspace output exceeded {limits['file_max_bytes']} bytes "
                    f"(observed at least {final_workspace['bytes']} bytes)"
                )

    stdout = stdout_capture.get("value", "")
    stderr = stderr_capture.get("value", "")
    stderr_truncated = bool(stderr_capture.get("truncated"))
    if resource_violation:
        resource_message = (
            "[sandbox resource limit] " + resource_violation["reason"]
            + "; process group killed"
        )
        combined_stderr = (stderr + "\n" + resource_message).strip()
        stderr_truncated = stderr_truncated or (
            len(combined_stderr.encode("utf-8")) > limits["stderr_max_bytes"]
        )
        stderr = _truncate(combined_stderr, limits["stderr_max_bytes"])
        returncode = _SCRIPT_RESOURCE_EXIT_CODE
    elif timed_out:
        timeout_message = (
            f"[sandbox timeout] exceeded {limits['timeout_seconds']} seconds; process group killed"
        )
        combined_stderr = (stderr + "\n" + timeout_message).strip()
        stderr_truncated = stderr_truncated or (
            len(combined_stderr.encode("utf-8")) > limits["stderr_max_bytes"]
        )
        stderr = _truncate(combined_stderr, limits["stderr_max_bytes"])
        returncode = 124
    else:
        returncode = process.returncode
    outputs = {
        "engine": task_type,
        "stdoutTruncated": bool(stdout_capture.get("truncated")),
        "stderrTruncated": stderr_truncated,
        "effectiveMaxProcesses": limits["effective_max_processes"],
    }
    if resource_violation:
        outputs["resourceLimit"] = resource_violation["reason"]
    result = subprocess.CompletedProcess(["sandbox", task_type.lower()], returncode, stdout, stderr)
    result.task_outputs = outputs
    return result


def _is_trino_ctas(sql):
    return bool(re.match(
        r"^\s*(?:(?:--[^\r\n]*(?:\r?\n|$))|(?:/\*.*?\*/\s*))*"
        r"CREATE\s+TABLE\b.*?\bAS\s+(?:SELECT|WITH)\b",
        sql or "",
        flags=re.IGNORECASE | re.DOTALL,
    ))


def _materializes_asset(node):
    if node.get("task_type") in _CONTROL_TASK_TYPES:
        return False
    if node.get("task_type") in _OBSERVE_WAIT_TASK_TYPES:
        return False
    if node.get("task_type") in _EXTENSION_TASK_TYPES:
        return False
    if node.get("task_type") in _SCRIPT_TASK_TYPES:
        return False
    if node.get("task_type") == "TRINO_SQL":
        return bool(node.get("target_fqn")) and _is_trino_ctas(node.get("sql_or_script"))
    return True


class _TrinoExecutionCancelled(Exception):
    pass


def _trino_log_value(value):
    """Return a bounded, JSON-safe preview without copying an entire large cell."""
    if value is None or isinstance(value, (bool, int, float)):
        return value, False
    if isinstance(value, str):
        preview = value[:_TRINO_LOG_CELL_PREVIEW_CHARS]
        if len(value) <= len(preview):
            return preview, False
        return preview + "... [value truncated]", True
    if isinstance(value, (bytes, bytearray, memoryview)):
        raw = bytes(value[:_TRINO_LOG_CELL_PREVIEW_CHARS])
        preview = raw.hex()
        if len(value) <= len(raw):
            return preview, False
        return preview + "... [binary value truncated]", True
    if isinstance(value, (list, tuple, dict, set)):
        return f"<{type(value).__name__} value omitted from log>", True
    preview = str(value)[:_TRINO_LOG_CELL_PREVIEW_CHARS]
    return preview, False


def _bounded_trino_log_row(row, byte_budget):
    """Build one row preview without exceeding its remaining JSON byte budget."""
    values = []
    used = 2  # JSON array brackets
    truncated = False
    for value in row:
        preview, value_truncated = _trino_log_value(value)
        truncated = truncated or value_truncated
        encoded = json.dumps(preview, ensure_ascii=False, default=str).encode("utf-8")
        needed = len(encoded) + (1 if values else 0)
        if used + needed > byte_budget:
            truncated = True
            break
        values.append(preview)
        used += needed
    return values, used, truncated


def _execute_trino_sql(
        node, cancellation_event=None, register_cursor=None, unregister_cursor=None):
    """Execute one approved TRINO_SQL node through the data-plane Trino service."""
    from trino.dbapi import connect

    host = os.getenv("TRINO_HOST", "trino")
    port = int(os.getenv("TRINO_PORT", "8080"))
    user = os.getenv("TRINO_USER", "onelake_pipeline")
    catalog = str(node.get("catalog") or "iceberg")
    schema = str(node.get("schema") or "default")
    sql = str(node.get("sql_or_script") or "").strip()
    if not sql:
        raise ValueError("TRINO_SQL sql_or_script must not be empty")

    connection = None
    cursor = None
    try:
        if cancellation_event is not None and cancellation_event.is_set():
            raise _TrinoExecutionCancelled("Trino query cancelled before connection")
        connection = connect(
            host=host,
            port=port,
            user=user,
            catalog=catalog,
            schema=schema,
            request_timeout=float(os.getenv("TRINO_REQUEST_TIMEOUT", "60")),
        )
        cursor = connection.cursor()
        if register_cursor is not None:
            register_cursor(cursor)
        if cancellation_event is not None and cancellation_event.is_set():
            raise _TrinoExecutionCancelled("Trino query cancelled before execution")
        cursor.execute(sql)

        max_rows = min(1000, max(0, int(os.getenv("TRINO_LOG_MAX_ROWS", "100"))))
        max_log_bytes = min(
            _TRINO_LOG_HARD_MAX_BYTES,
            max(0, int(os.getenv(
                "TRINO_LOG_MAX_BYTES", str(_TRINO_LOG_DEFAULT_MAX_BYTES),
            ))),
        )
        fetch_size = min(1000, max(1, int(os.getenv("TRINO_FETCH_BATCH_SIZE", "1000"))))
        rows = []
        rows_json_bytes = 2  # JSON outer array brackets
        value_truncated = False
        result_rows = 0
        first_result_row = None
        description = getattr(cursor, "description", None)
        if description:
            while True:
                if cancellation_event is not None and cancellation_event.is_set():
                    raise _TrinoExecutionCancelled("Trino query cancelled while fetching results")
                batch = list(cursor.fetchmany(fetch_size))
                if not batch:
                    break
                if first_result_row is None:
                    first_result_row = batch[0]
                result_rows += len(batch)
                remaining = max_rows - len(rows)
                if remaining > 0 and rows_json_bytes < max_log_bytes:
                    for row in batch[:remaining]:
                        row_budget = max_log_bytes - rows_json_bytes - (1 if rows else 0)
                        if row_budget < 2:
                            value_truncated = True
                            break
                        preview, encoded_bytes, row_truncated = _bounded_trino_log_row(
                            row, row_budget,
                        )
                        if not preview and row:
                            value_truncated = True
                            break
                        rows.append(preview)
                        rows_json_bytes += encoded_bytes + (1 if len(rows) > 1 else 0)
                        value_truncated = value_truncated or row_truncated
        truncated = result_rows > len(rows) or value_truncated

        stats = getattr(cursor, "stats", None) or {}
        query_id = stats.get("queryId") or getattr(cursor, "query_id", None)
        columns = []
        for column in description or []:
            if isinstance(column, (tuple, list)) and column:
                columns.append(str(column[0]))
            else:
                columns.append(str(getattr(column, "name", column)))

        outputs = {"engine": "TRINO", "resultRows": result_rows}
        if query_id:
            outputs["queryId"] = str(query_id)
        update_count = getattr(cursor, "update_count", None)
        if _is_trino_ctas(sql):
            rows_written = update_count
            # Trino returns the CTAS update count as a single result row on current
            # releases, while older connector versions may expose update_count.
            if rows_written is None and first_result_row:
                rows_written = first_result_row[0]
            if (isinstance(rows_written, int)
                    and not isinstance(rows_written, bool)
                    and rows_written >= 0):
                outputs["rowsWritten"] = rows_written

        lines = [
            f"[trino] host={host} port={port} user={user} catalog={catalog} schema={schema}",
            "[sql]",
            sql,
        ]
        if query_id:
            lines.append(f"[query_id] {query_id}")
        if columns:
            lines.extend([
                "[columns] " + json.dumps(columns, ensure_ascii=False),
                "[rows] " + json.dumps(
                    rows, ensure_ascii=False, default=str, separators=(",", ":"),
                ),
            ])
            if truncated:
                lines.append(
                    f"[rows_truncated] bounded to {max_rows} rows / {max_log_bytes} bytes"
                )
        if update_count is not None:
            lines.append(f"[update_count] {update_count}")
        lines.append(_TASK_OUTPUTS_MARKER + json.dumps(outputs, ensure_ascii=False))
        return subprocess.CompletedProcess(
            ["trino", f"{host}:{port}", catalog, schema], 0, "\n".join(lines), "",
        )
    except _TrinoExecutionCancelled:
        raise
    except Exception as exc:
        if cancellation_event is not None and cancellation_event.is_set():
            raise _TrinoExecutionCancelled("Trino query cancelled") from exc
        raise RuntimeError(
            f"Trino query failed catalog={catalog} schema={schema}: {exc}"
        ) from exc
    finally:
        if cursor is not None and unregister_cursor is not None:
            unregister_cursor(cursor)
        for resource in (cursor, connection):
            if resource is not None:
                try:
                    resource.close()
                except Exception:
                    pass


def _extract_task_outputs(stdout):
    """Read the last structured output marker emitted by a task executor."""
    outputs = {}
    for line in (stdout or "").splitlines():
        marker_index = line.find(_TASK_OUTPUTS_MARKER)
        if marker_index < 0:
            continue
        raw = line[marker_index + len(_TASK_OUTPUTS_MARKER):].strip()
        try:
            parsed = json.loads(raw)
        except (TypeError, ValueError):
            continue
        if isinstance(parsed, dict):
            outputs = parsed
    rows_written = outputs.get("rowsWritten")
    if rows_written is not None:
        if isinstance(rows_written, bool) or not isinstance(rows_written, int) or rows_written < 0:
            raise ValueError("task outputs rowsWritten must be a non-negative integer")
    return outputs


def _success_callback_payload(node, result, **fields):
    outputs = getattr(result, "task_outputs", None)
    if not isinstance(outputs, dict):
        outputs = _extract_task_outputs(getattr(result, "stdout", ""))
    payload = dict(fields)
    payload["status"] = "SUCCEEDED"
    payload["artifactPath"] = _table_artifact(node)
    payload["outputs"] = outputs
    if outputs.get("rowsWritten") is not None:
        payload["rowsWritten"] = outputs["rowsWritten"]
    return payload


def _runtime_params(config):
    params = {}
    for item in config.get("runtime_params") or []:
        key = str(item.get("key") or "").strip()
        if key:
            params[key] = "" if item.get("value") is None else str(item.get("value"))
    return params


def _apply_runtime_params(text, params):
    result = "" if text is None else str(text)
    for key, value in (params or {}).items():
        result = result.replace("${" + key + "}", value)
    return result


def _node_with_runtime_params(node, params):
    enriched = dict(node)
    enriched["sql_or_script"] = _apply_runtime_params(enriched.get("sql_or_script", ""), params)
    enriched["expression"] = _apply_runtime_params(enriched.get("expression", ""), params)
    enriched["notification_title"] = _apply_runtime_params(
        enriched.get("notification_title", ""), params,
    )
    enriched["notification_message"] = _apply_runtime_params(
        enriched.get("notification_message", ""), params,
    )
    enriched["notification_link"] = _apply_runtime_params(
        enriched.get("notification_link", ""), params,
    )
    return enriched


@op(
    name="run_spark_task_op",
    config_schema={
        "pipeline_id": Field(String),
        "run_id": Field(String),
        "tenant_id": Field(String),
        "resource_profile": Field(
            Shape({
                "executor_memory": Field(String, default_value="2g"),
                "executor_cores": Field(String, default_value="2"),
                "num_executors": Field(String, default_value="2"),
                "driver_memory": Field(String, default_value="1g"),
            }),
            default_value={
                "executor_memory": "2g",
                "executor_cores": "2",
                "num_executors": "2",
                "driver_memory": "1g",
            },
        ),
        "iceberg_catalog": Field(String, default_value="onelake"),
        "callback_base_url": Field(String, default_value=""),
        "runtime_params": Field(
            Array(Shape({
                "key": Field(String),
                "value": Field(String, default_value=""),
            })),
            default_value=[],
        ),
        "tasks": Field(
            Array(Shape({
                "task_key": Field(String),
                "task_type": Field(String, description="SPARK_SQL | PYSPARK | QUALITY_GATE"),
                "engine": Field(String, default_value="SPARK"),
                "sql_or_script": Field(String, description="Spark SQL string or PySpark script content"),
                "target_fqn": Field(String, default_value=""),
                "from_tables": Field(Array(String), default_value=[]),
                "upstream_task_keys": Field(Array(String), default_value=[]),
                "resource_profile": Field(
                    Shape({
                        "executor_memory": Field(String, default_value="2g"),
                        "executor_cores": Field(String, default_value="2"),
                        "num_executors": Field(String, default_value="2"),
                        "driver_memory": Field(String, default_value="1g"),
                    }),
                    default_value={
                        "executor_memory": "2g",
                        "executor_cores": "2",
                        "num_executors": "2",
                        "driver_memory": "1g",
                    },
                ),
            })),
            default_value=[],
        ),
    },
)
def run_spark_task_op(context):
    """Run one Spark task via spark-submit.

    C5 (§6.4): resource_profile is honored here — mapped to spark-submit flags.
    The unified pipeline mainline is Spark-only; upstream readiness is enforced by
    orchestration.task_run and pipeline_task_edge before this op is launched.
    """
    cfg = context.op_config
    base_url = cfg.get("callback_base_url", "")
    runtime_params = _runtime_params(cfg)
    iceberg_catalog = cfg["iceberg_catalog"]
    spark_master = os.getenv("SPARK_MASTER_URL", "local[2]")
    tasks = cfg.get("tasks") or []
    if not tasks:
        context.log.info(
            "OneLake Spark pipeline=%s run=%s tenant=%s has no Spark tasks; skipping",
            cfg["pipeline_id"], cfg["run_id"], cfg["tenant_id"],
        )
        return {
            "pipeline_id": cfg["pipeline_id"],
            "run_id": cfg["run_id"],
            "tasks": [],
        }

    results = []
    for task in tasks:
        task_type = task["task_type"]
        resource = task.get("resource_profile") or cfg["resource_profile"]
        context.log.info(
            "OneLake Spark task pipeline=%s run=%s tenant=%s task=%s type=%s resource=%s from=%s target=%s",
            cfg["pipeline_id"], cfg["run_id"], cfg["tenant_id"], task["task_key"],
            task_type, resource, task.get("from_tables", []), task.get("target_fqn", ""),
        )

        node = _node_with_runtime_params(task, runtime_params)
        node["resource_profile"] = resource
        temp_paths = []
        log_ref = ""
        terminal_callback_sent = False
        _callback(base_url, cfg["run_id"], task["task_key"], {
            "status": "RUNNING",
            "startedAt": _now(),
            "dagsterStepKey": task["task_key"],
        }, context.log)
        try:
            node = _render_node_config(base_url, cfg["run_id"], task["task_key"], node)
            cmd, temp_paths = _build_spark_submit(node, iceberg_catalog, spark_master)
            context.log.info("spark-submit cmd: %s", " ".join(cmd))
            result = subprocess.run(
                cmd,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            if result.stdout:
                context.log.info(result.stdout)
            if result.stderr:
                context.log.warning(result.stderr)
            log_ref = _upload_log(
                cfg["tenant_id"],
                cfg["run_id"],
                task["task_key"],
                1,
                _spark_log_content(cmd, result),
                context.log,
            )

            if result.returncode != 0:
                message = f"spark-submit failed for task {task['task_key']} (exit {result.returncode})"
                _callback(base_url, cfg["run_id"], task["task_key"], {
                    "status": "FAILED",
                    "finishedAt": _now(),
                    "errorMsg": _tail(message, 3900),
                    "logRef": log_ref,
                    "attempt": 1,
                }, context.log)
                terminal_callback_sent = True
                raise RuntimeError(message)

            _callback(base_url, cfg["run_id"], task["task_key"],
                      _success_callback_payload(
                          node, result, finishedAt=_now(), logRef=log_ref, attempt=1),
                      context.log)
            terminal_callback_sent = True

            results.append({
                "task_key": task["task_key"],
                "task_type": task_type,
                "exit_code": result.returncode,
                "target_fqn": task.get("target_fqn", ""),
                "log_ref": log_ref,
            })
        except Exception as exc:
            if not log_ref:
                log_ref = _upload_log(
                    cfg["tenant_id"],
                    cfg["run_id"],
                    task["task_key"],
                    1,
                    str(exc),
                    context.log,
                )
            if not terminal_callback_sent:
                _callback(base_url, cfg["run_id"], task["task_key"], {
                    "status": "FAILED",
                    "finishedAt": _now(),
                    "errorMsg": _tail(str(exc), 3900),
                    "logRef": log_ref,
                    "attempt": 1,
                }, context.log)
            raise
        finally:
            for path in temp_paths:
                try:
                    os.unlink(path)
                except OSError:
                    pass
    return {
        "pipeline_id": cfg["pipeline_id"],
        "run_id": cfg["run_id"],
        "tasks": results,
    }


@job(name="onelake_pipeline_run")
def onelake_pipeline_run():
    """Pipeline v2 job: Spark-only pipeline execution."""
    run_spark_task_op()


# ---------------------------------------------------------------------------
# Pipeline graph op — fixed Dagster job with in-op DAG scheduling.
# ---------------------------------------------------------------------------


def _now():
    return datetime.now(timezone.utc).isoformat()


def _tail(value, max_length):
    if value is None or len(value) <= max_length:
        return value
    return value[-max_length:]


def _truncate(content, max_bytes=None):
    max_bytes = int(max_bytes or os.getenv("ONELAKE_LOG_MAX_BYTES", str(5 * 1024 * 1024)))
    text = "" if content is None else str(content)
    data = text.encode("utf-8")
    if max_bytes <= 0 or len(data) <= max_bytes:
        return text

    marker = (
        f"\n... [log truncated: original_bytes={len(data)} max_bytes={max_bytes}] ...\n"
    ).encode("utf-8")
    if len(marker) >= max_bytes:
        return data[:max_bytes].decode("utf-8", errors="ignore")

    head_bytes = max_bytes // 5
    tail_bytes = max_bytes - head_bytes - len(marker)
    return (
        data[:head_bytes].decode("utf-8", errors="ignore")
        + marker.decode("utf-8")
        + data[-tail_bytes:].decode("utf-8", errors="ignore")
    )


def _table_artifact(node):
    fqn = node.get("target_fqn") or ""
    return f"table:{fqn}" if fqn else ""


def _callback(base_url, run_id, task_key, payload, log):
    import requests

    payload = _normalize_callback_payload(payload)
    terminal_status = payload.get("status")
    requires_ack = terminal_status in {"SUCCEEDED", "SKIPPED"}
    target_base_url = (base_url or os.getenv("ONELAKE_CALLBACK_BASE_URL", "")).rstrip("/")
    if not target_base_url:
        message = f"callback base URL is empty for run={run_id} task={task_key}"
        if requires_ack:
            raise _CallbackDeliveryError(message)
        log.info("%s; payload=%s", message, payload)
        return

    token = os.getenv("ONELAKE_INTERNAL_TOKEN", "") or os.getenv("ONELAKE_ORCHESTRATION_INTERNAL_TOKEN", "")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Onelake-Internal-Token"] = token
    url = (
        f"{target_base_url}/api/v1/internal/orchestration/runs/{run_id}"
        f"/tasks/{quote(str(task_key), safe='')}/status"
    )
    attempts = 3 if requires_ack else 1
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            resp = requests.post(url, json=payload, headers=headers, timeout=5)
            resp.raise_for_status()
            if requires_ack:
                response_body = resp.json()
                current_status = (response_body.get("data") or {}).get("currentStatus")
                if current_status != terminal_status:
                    raise RuntimeError(
                        f"{terminal_status} callback was not applied; "
                        f"authoritative status={current_status}"
                    )
            return
        except Exception as exc:
            last_error = exc
            log.warning(
                "callback failed run=%s task=%s url=%s attempt=%s/%s error=%s",
                run_id, task_key, url, attempt, attempts, exc,
            )
            if attempt < attempts:
                time.sleep(0.2 * attempt)
    if requires_ack:
        raise _CallbackDeliveryError(
            f"{terminal_status} callback was not acknowledged "
            f"for run={run_id} task={task_key}"
        ) from last_error


class _CallbackDeliveryError(RuntimeError):
    """A success-like terminal state must be persisted before graph scheduling continues."""


def _normalize_callback_payload(payload):
    normalized = dict(payload or {})
    if normalized.get("status") != "SUCCEEDED":
        return normalized
    outputs = dict(normalized.get("outputs") or {})
    if normalized.get("rowsWritten") is not None:
        outputs["rowsWritten"] = normalized["rowsWritten"]
    if normalized.get("artifactPath") is not None:
        outputs["artifactPath"] = normalized["artifactPath"]
    normalized["outputs"] = outputs
    return normalized


def _contains_upstream_placeholder(value):
    if isinstance(value, str):
        return "${upstream." in value
    if isinstance(value, dict):
        return any(_contains_upstream_placeholder(item) for item in value.values())
    if isinstance(value, (list, tuple)):
        return any(_contains_upstream_placeholder(item) for item in value)
    return False


def _render_node_config(base_url, run_id, task_key, node):
    """Ask Java to resolve upstream outputs immediately before this Dagster step executes."""
    if not _contains_upstream_placeholder(node):
        return node

    import requests

    target_base_url = (base_url or os.getenv("ONELAKE_CALLBACK_BASE_URL", "")).rstrip("/")
    if not target_base_url:
        raise RuntimeError(
            f"node {task_key} references upstream outputs but callback base URL is empty"
        )
    token = os.getenv("ONELAKE_INTERNAL_TOKEN", "") or os.getenv(
        "ONELAKE_ORCHESTRATION_INTERNAL_TOKEN", ""
    )
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Onelake-Internal-Token"] = token
    url = (
        f"{target_base_url}/api/v1/internal/orchestration/runs/{run_id}"
        f"/tasks/{quote(str(task_key), safe='')}/render-config"
    )
    response = requests.post(
        url,
        json={
            "config": node,
            "upstreamTaskKeys": list(node.get("upstream_task_keys") or []),
        },
        headers=headers,
        timeout=5,
    )
    try:
        body = response.json()
    except ValueError:
        body = {}
    if not response.ok:
        message = body.get("message") or response.text or response.reason
        raise RuntimeError(f"node {task_key} upstream output render failed: {message}")
    rendered = (body.get("data") or {}).get("config")
    if not isinstance(rendered, dict):
        raise RuntimeError(f"node {task_key} upstream output render returned invalid config")
    return rendered


def _safe_key_part(value):
    return quote(str(value), safe="._=-")


def _redact_arg(value):
    text = str(value)
    lower = text.lower()
    secret_markers = (
        "secret",
        "password",
        "token",
        "access-key",
        "access_key",
        "access.key",
        "credential",
    )
    if not any(marker in lower for marker in secret_markers):
        return text
    if "=" in text:
        key, _ = text.split("=", 1)
        return f"{key}=***REDACTED***"
    return "***REDACTED***"


def _spark_log_content(cmd, result):
    parts = [
        "$ " + " ".join(_redact_arg(arg) for arg in cmd),
        f"[exit_code] {result.returncode}",
    ]
    if result.stdout:
        parts.extend(["[stdout]", result.stdout])
    if result.stderr:
        parts.extend(["[stderr]", result.stderr])
    return "\n".join(parts)


def _upload_log(tenant_id, run_id, task_key, attempt, content, log):
    try:
        import boto3

        bucket = os.getenv("ONELAKE_LOG_BUCKET", "onelake-logs")
        endpoint = os.getenv("MINIO_ENDPOINT", "http://minio:9000")
        access_key = os.getenv("AWS_ACCESS_KEY_ID", "minio")
        secret_key = os.getenv("AWS_SECRET_ACCESS_KEY", "minio12345")
        body = _truncate(content).encode("utf-8")
        safe_task_key = _safe_key_part(task_key)
        safe_attempt = max(1, int(attempt or 1))
        prefix = f"{tenant_id}/{run_id}/{safe_task_key}"
        attempt_key = f"{prefix}/attempt-{safe_attempt}.log"
        latest_key = f"{prefix}/latest.log"
        client = boto3.client(
            "s3",
            endpoint_url=endpoint,
            aws_access_key_id=access_key,
            aws_secret_access_key=secret_key,
            region_name=os.getenv("AWS_DEFAULT_REGION", "us-east-1"),
        )
        for key in (attempt_key, latest_key):
            client.put_object(
                Bucket=bucket,
                Key=key,
                Body=body,
                ContentType="text/plain; charset=utf-8",
            )
        log.info(
            "uploaded task log tenant=%s run=%s task=%s attempt=%s bucket=%s key=%s bytes=%s",
            tenant_id,
            run_id,
            task_key,
            safe_attempt,
            bucket,
            latest_key,
            len(body),
        )
        return latest_key
    except Exception as exc:
        log.warning(
            "task log upload failed tenant=%s run=%s task=%s attempt=%s error=%s",
            tenant_id,
            run_id,
            task_key,
            attempt,
            exc,
        )
        return ""


_NATIVE_GRAPH_JOB_PREFIX = "onelake_pipeline_graph_"
_TASK_KEY_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")
_NATIVE_TASK_OPS = {}


_SCRIPT_NODE_CONFIG = {
    "timeout_seconds": Field(Int, default_value=_SCRIPT_DEFAULTS["timeout_seconds"]),
    "cpu_seconds": Field(Int, default_value=_SCRIPT_DEFAULTS["cpu_seconds"]),
    "cpu_cores": Field(Int, default_value=_SCRIPT_DEFAULTS["cpu_cores"]),
    "memory_mb": Field(Int, default_value=_SCRIPT_DEFAULTS["memory_mb"]),
    "max_processes": Field(Int, default_value=_SCRIPT_DEFAULTS["max_processes"]),
    "max_files": Field(Int, default_value=_SCRIPT_DEFAULTS["max_files"]),
    "file_max_bytes": Field(Int, default_value=_SCRIPT_DEFAULTS["file_max_bytes"]),
    "stdout_max_bytes": Field(Int, default_value=_SCRIPT_DEFAULTS["stdout_max_bytes"]),
    "stderr_max_bytes": Field(Int, default_value=_SCRIPT_DEFAULTS["stderr_max_bytes"]),
    "env": Field(
        Array(Shape({"key": Field(String), "value": Field(String, default_value="")})),
        default_value=[],
    ),
    "network_allowlist": Field(Array(String), default_value=[]),
}


_CONTROL_NODE_CONFIG = {
    "expression": Field(String, default_value=""),
    "branches": Field(
        Array(Shape({
            "value": Field(String),
            "targets": Field(Array(String)),
        })),
        default_value=[],
    ),
}


_OBSERVE_NODE_CONFIG = {
    "sensor_asset_fqn": Field(String, default_value=""),
    "sensor_partition": Field(String, default_value=""),
    "poll_interval_seconds": Field(Int, default_value=5),
    "on_timeout": Field(String, default_value="FAILED"),
    "wait_offset_seconds": Field(Int, default_value=-1),
    "wait_duration_seconds": Field(Int, default_value=-1),
}


_EXTENSION_NODE_CONFIG = {
    "sub_dag_id": Field(String, default_value=""),
    "wait_for_completion": Field(Bool, default_value=False),
    "sub_timeout_seconds": Field(Int, default_value=3600),
    "sub_poll_interval_seconds": Field(Int, default_value=5),
    "notification_receiver_id": Field(String, default_value=""),
    "notification_title": Field(String, default_value=""),
    "notification_message": Field(String, default_value=""),
    "notification_level": Field(String, default_value="INFO"),
    "notification_link": Field(String, default_value=""),
}


_NATIVE_NODE_CONFIG = {
    "pipeline_id": Field(String),
    "run_id": Field(String),
    "tenant_id": Field(String),
    "task_key": Field(String),
    "task_type": Field(String),
    "engine": Field(String, default_value="SPARK"),
    "catalog": Field(String, default_value="iceberg"),
    "schema": Field(String, default_value="default"),
    "sql_or_script": Field(String, default_value=""),
    "target_fqn": Field(String, default_value=""),
    "from_tables": Field(Array(String), default_value=[]),
    "upstream_task_keys": Field(Array(String), default_value=[]),
    "resource_profile": Field(
        Shape({
            "executor_memory": Field(String, default_value="2g"),
            "executor_cores": Field(String, default_value="2"),
            "num_executors": Field(String, default_value="2"),
            "driver_memory": Field(String, default_value="1g"),
        }),
        is_required=False,
    ),
    "base_attempt": Field(Int, default_value=1),
    "max_retries": Field(Int, default_value=0),
    "iceberg_catalog": Field(String, default_value="onelake"),
    "callback_base_url": Field(String, default_value=""),
    "runtime_params": Field(
        Array(Shape({"key": Field(String), "value": Field(String, default_value="")})),
        default_value=[],
    ),
    **_CONTROL_NODE_CONFIG,
    **_SCRIPT_NODE_CONFIG,
    **_OBSERVE_NODE_CONFIG,
    **_EXTENSION_NODE_CONFIG,
}


def _execute_native_graph_node(context):
    """Execute one pipeline task in its own Dagster step.

    The legacy graph executor below stays intact for rollout compatibility. This path is
    used by per-pipeline jobs built at code-location reload time, so Dagster itself owns
    dependency, skip and retry visibility instead of an internal thread pool.
    """
    cfg = context.op_config
    task_key = cfg["task_key"]
    runtime_params = _runtime_params(cfg)
    node = _node_with_runtime_params(cfg, runtime_params)
    base_url = cfg.get("callback_base_url", "")
    run_id = cfg["run_id"]
    tenant_id = cfg["tenant_id"]
    iceberg_catalog = cfg.get("iceberg_catalog", "onelake")
    spark_master = os.getenv("SPARK_MASTER_URL", "local[2]")
    first_attempt = max(1, int(node.get("base_attempt", 1) or 1))

    _callback(base_url, run_id, task_key, {
        "status": "RUNNING", "startedAt": _now(), "attempt": first_attempt,
        "dagsterStepKey": task_key,
    }, context.log)

    try:
        node = _render_node_config(base_url, run_id, task_key, node)
    except Exception as exc:
        message = str(exc)
        log_ref = _upload_log(tenant_id, run_id, task_key, first_attempt, message, context.log)
        _callback(base_url, run_id, task_key, {
            "status": "FAILED", "finishedAt": _now(), "attempt": first_attempt,
            "dagsterStepKey": task_key, "errorMsg": _tail(message, 3900), "logRef": log_ref,
        }, context.log)
        raise Failure(description=message) from exc

    if node["task_type"] in _CONTROL_TASK_TYPES:
        message = (
            f"{node['task_type']} requires the control graph executor "
            "onelake_pipeline_graph_run"
        )
        log_ref = _upload_log(tenant_id, run_id, task_key, first_attempt, message, context.log)
        _callback(base_url, run_id, task_key, {
            "status": "FAILED", "finishedAt": _now(), "attempt": first_attempt,
            "dagsterStepKey": task_key, "errorMsg": message, "logRef": log_ref,
        }, context.log)
        raise Failure(description=message)

    if node["task_type"] == "SYNC_REF":
        log_ref = _upload_log(tenant_id, run_id, task_key, first_attempt, "SYNC_REF completed", context.log)
        _callback(base_url, run_id, task_key, {
            "status": "SUCCEEDED", "finishedAt": _now(), "attempt": first_attempt,
            "dagsterStepKey": task_key, "artifactPath": _table_artifact(node), "logRef": log_ref,
        }, context.log)
        context.log_event(AssetMaterialization(
            asset_key=node.get("target_fqn") or task_key,
            description=f"pipeline node {task_key}",
        ))
        return "SUCCEEDED"

    if node["task_type"] in _OBSERVE_WAIT_TASK_TYPES:
        observe_status, message = _run_observe_wait_with_callback(
            node, runtime_params, base_url, run_id, tenant_id, task_key,
            first_attempt, threading.Event(), context.log,
        )
        if observe_status == "FAILED":
            raise Failure(description=message)
        return observe_status

    if node["task_type"] in _EXTENSION_TASK_TYPES:
        extension_status, message = _run_extension_with_callback(
            node, base_url, run_id, tenant_id, task_key,
            first_attempt, threading.Event(), context.log,
        )
        if extension_status == "FAILED":
            raise Failure(description=message)
        return extension_status

    attempts = max(1, int(node.get("max_retries", 0)) + 1)
    last_log = ""
    last_log_ref = ""
    for local_attempt in range(1, attempts + 1):
        attempt = first_attempt + local_attempt - 1
        temp_paths = []
        process = None
        trino_cursor = None
        cancellation_event = threading.Event()
        previous_signal_handlers = {}

        def terminate_process_group(reason):
            if process is None or process.poll() is not None:
                return
            try:
                context.log.warning(
                    "terminating native task process group pid=%s reason=%s",
                    process.pid,
                    reason,
                )
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                return
            except Exception as exc:
                context.log.warning(
                    "failed to terminate native task process group pid=%s: %s",
                    process.pid,
                    exc,
                )
                return
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    context.log.warning(
                        "force killing native task process group pid=%s",
                        process.pid,
                    )
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except Exception as exc:
                    context.log.warning(
                        "failed to force kill native task process group pid=%s: %s",
                        process.pid,
                        exc,
                    )
                try:
                    process.wait(timeout=2)
                except Exception:
                    pass

        def register_trino_cursor(cursor):
            nonlocal trino_cursor
            trino_cursor = cursor

        def unregister_trino_cursor(cursor):
            nonlocal trino_cursor
            if trino_cursor is cursor:
                trino_cursor = None

        def register_script_process(script_process):
            nonlocal process
            process = script_process

        def unregister_script_process(script_process):
            nonlocal process
            if process is script_process:
                process = None

        def terminate_trino_query(reason):
            cancellation_event.set()
            if trino_cursor is None:
                return
            try:
                context.log.warning(
                    "cancelling native Trino query reason=%s",
                    reason,
                )
                trino_cursor.cancel()
            except Exception as exc:
                context.log.warning("failed to cancel native Trino query: %s", exc)

        def handle_termination_signal(signum, frame):
            reason = signal.Signals(signum).name
            terminate_process_group(reason)
            terminate_trino_query(reason)
            raise KeyboardInterrupt(reason)

        def install_signal_handlers():
            if threading.current_thread() is not threading.main_thread():
                return
            for signum in (signal.SIGTERM, signal.SIGINT):
                previous_signal_handlers[signum] = signal.getsignal(signum)
                signal.signal(signum, handle_termination_signal)

        def restore_signal_handlers():
            for signum, handler in previous_signal_handlers.items():
                signal.signal(signum, handler)

        def is_execution_interrupt(exc):
            return isinstance(exc, (KeyboardInterrupt, SystemExit)) or (
                exc.__class__.__name__ == "DagsterExecutionInterruptedError"
            )

        try:
            install_signal_handlers()
            if node["task_type"] == "TRINO_SQL":
                context.log.info("[%s] attempt %s executing with Trino", task_key, attempt)
                result = _execute_trino_sql(
                    node,
                    cancellation_event=cancellation_event,
                    register_cursor=register_trino_cursor,
                    unregister_cursor=unregister_trino_cursor,
                )
                cmd = result.args
            elif node["task_type"] in _SCRIPT_TASK_TYPES:
                context.log.info(
                    "[%s] attempt %s executing in %s sandbox",
                    task_key, attempt, node["task_type"],
                )
                result = _execute_sandboxed_script(
                    node,
                    register_process=register_script_process,
                    unregister_process=unregister_script_process,
                )
                cmd = result.args
            else:
                cmd, temp_paths = _build_spark_submit(node, iceberg_catalog, spark_master)
                context.log.info("[%s] attempt %s spark-submit cmd: %s", task_key, attempt, " ".join(cmd))
                process = subprocess.Popen(
                    cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
                )
                stdout, stderr = process.communicate()
                result = subprocess.CompletedProcess(cmd, process.returncode, stdout, stderr)
            last_log = _spark_log_content(cmd, result)
            last_log_ref = _upload_log(tenant_id, run_id, task_key, attempt, last_log, context.log)
            if result.returncode == 0:
                _callback(base_url, run_id, task_key,
                          _success_callback_payload(
                              node, result, finishedAt=_now(), attempt=attempt,
                              dagsterStepKey=task_key, logRef=last_log_ref),
                          context.log)
                if _materializes_asset(node):
                    context.log_event(AssetMaterialization(
                        asset_key=node.get("target_fqn") or task_key,
                        description=f"pipeline node {task_key}",
                    ))
                return "SUCCEEDED"
            context.log.warning("[%s] attempt %s failed with exit code %s", task_key, attempt, result.returncode)
        except _CallbackDeliveryError:
            # The Spark write already succeeded. Retrying the attempt could duplicate data;
            # fail this Dagster step and keep all dependants blocked instead.
            raise
        except BaseException as exc:
            last_log = str(exc)
            last_log_ref = _upload_log(tenant_id, run_id, task_key, attempt, last_log, context.log)
            if is_execution_interrupt(exc):
                terminate_process_group(exc.__class__.__name__)
                terminate_trino_query(exc.__class__.__name__)
                raise
            context.log.warning("[%s] attempt %s failed: %s", task_key, attempt, exc)
        finally:
            restore_signal_handlers()
            for path in temp_paths:
                try:
                    os.unlink(path)
                except OSError:
                    pass

    _callback(base_url, run_id, task_key, {
        "status": "FAILED", "finishedAt": _now(), "attempt": first_attempt + attempts - 1,
        "dagsterStepKey": task_key, "errorMsg": _tail(last_log, 3900), "logRef": last_log_ref,
    }, context.log)
    raise Failure(description=f"pipeline task {task_key} failed")


def _make_native_task_op(task_key, upstream_count):
    cached = _NATIVE_TASK_OPS.get(task_key)
    # A repository reload can observe the same business taskKey with a wider fan-in
    # than a previous load. Reuse is safe only when the cached op already exposes
    # enough Nothing inputs; otherwise rebuild and replace it before constructing
    # the new repository snapshot.
    if cached is not None and len(cached.input_defs) >= upstream_count:
        return cached
    inputs = {f"upstream_{index}": In(Nothing) for index in range(upstream_count)}

    @op(
        name=task_key,
        ins=inputs,
        out=Out(Nothing, is_required=False),
        config_schema=_NATIVE_NODE_CONFIG,
    )
    def pipeline_task(context):
        result = _execute_native_graph_node(context)
        if result != "SKIPPED":
            yield Output(None)

    _NATIVE_TASK_OPS[task_key] = pipeline_task
    return pipeline_task


def _pipeline_graph_job_name(pipeline_id, version_id=None):
    name = _NATIVE_GRAPH_JOB_PREFIX + str(pipeline_id).replace("-", "")
    if version_id:
        name += "_v_" + str(version_id).replace("-", "")
    return name


def _build_native_pipeline_job(definition, upstream_slots=None):
    pipeline_id = definition.get("pipeline_id")
    version_id = definition.get("version_id")
    task_keys = definition.get("task_keys") or []
    if not pipeline_id or not task_keys or len(task_keys) != len(set(task_keys)):
        return None
    if any(not _TASK_KEY_PATTERN.match(str(key)) for key in task_keys):
        return None
    upstreams = {key: [] for key in task_keys}
    for edge in definition.get("edges") or []:
        source, target = edge.get("source_key"), edge.get("target_key")
        if source in upstreams and target in upstreams:
            upstreams[target].append(source)
    slots = upstream_slots or {}
    task_ops = {
        key: _make_native_task_op(key, max(len(upstreams[key]), slots.get(key, 0)))
        for key in task_keys
    }
    job_name = _pipeline_graph_job_name(pipeline_id, version_id)

    @graph(name=job_name)
    def pipeline_graph():
        outputs = {}
        pending = list(task_keys)
        while pending:
            ready = [
                key for key in pending
                if all(source in outputs for source in upstreams[key])
            ]
            if not ready:
                raise ValueError(f"pipeline graph has cyclic or unresolved inputs: {pending}")
            for key in ready:
                # Dagster does not resolve multi-input Nothing dependencies from positional
                # arguments reliably; bind every graph input by its declared name.
                inputs = {
                    f"upstream_{index}": outputs[source]
                    for index, source in enumerate(upstreams[key])
                }
                outputs[key] = task_ops[key](**inputs)
            pending = [key for key in pending if key not in ready]

    return pipeline_graph.to_job(name=job_name, executor_def=multiprocess_executor)


def _load_native_pipeline_jobs():
    base_url = os.getenv("ONELAKE_CALLBACK_BASE_URL", "").rstrip("/")
    token = os.getenv("ONELAKE_INTERNAL_TOKEN", "")
    if not base_url or not token:
        return []
    try:
        import requests
        response = requests.get(
            base_url + "/api/v1/internal/orchestration/dagster/graph-definitions",
            headers={"X-Onelake-Internal-Token": token}, timeout=5,
        )
        response.raise_for_status()
        payload = response.json()
        definitions = payload.get("data") or []
        upstream_slots = {}
        for definition in definitions:
            task_keys = set(definition.get("task_keys") or [])
            counts = {key: 0 for key in task_keys}
            for edge in definition.get("edges") or []:
                source, target = edge.get("source_key"), edge.get("target_key")
                if source in task_keys and target in task_keys:
                    counts[target] += 1
            for task_key, count in counts.items():
                upstream_slots[task_key] = max(upstream_slots.get(task_key, 0), count)

        jobs = []
        for definition in definitions:
            try:
                native_job = _build_native_pipeline_job(definition, upstream_slots)
                if native_job is not None:
                    jobs.append(native_job)
            except Exception as exc:
                pipeline_id = definition.get("pipeline_id", "unknown")
                print(f"OneLake skipped invalid native pipeline graph {pipeline_id}: {exc}")
        return jobs
    except Exception as exc:
        # The backend may be unavailable while the user-code container boots. A later reload before
        # GRAPH launch reconstructs the jobs; failure here must not take down all code locations.
        print(f"OneLake native pipeline graph definitions unavailable: {exc}")
        return []


@op(
    name="run_pipeline_graph_op",
    config_schema={
        "pipeline_id": Field(String),
        "run_id": Field(String),
        "tenant_id": Field(String),
        "iceberg_catalog": Field(String, default_value="onelake"),
        "execution_mode": Field(String, default_value="GRAPH"),
        "callback_base_url": Field(String, default_value=""),
        "runtime_params": Field(
            Array(Shape({
                "key": Field(String),
                "value": Field(String, default_value=""),
            })),
            default_value=[],
        ),
        "max_parallel": Field(Int, default_value=4),
        "nodes": Field(
            Array(Shape({
                "task_key": Field(String),
                "task_type": Field(String),
                "engine": Field(String, default_value="SPARK"),
                "catalog": Field(String, default_value="iceberg"),
                "schema": Field(String, default_value="default"),
                "sql_or_script": Field(String, default_value=""),
                "target_fqn": Field(String, default_value=""),
                "from_tables": Field(Array(String), default_value=[]),
                "upstream_task_keys": Field(Array(String), default_value=[]),
                "resource_profile": Field(
                    Shape({
                        "executor_memory": Field(String, default_value="2g"),
                        "executor_cores": Field(String, default_value="2"),
                        "num_executors": Field(String, default_value="2"),
                        "driver_memory": Field(String, default_value="1g"),
                    }),
                    is_required=False,
                ),
                "base_attempt": Field(Int, default_value=1),
                "max_retries": Field(Int, default_value=0),
                **_CONTROL_NODE_CONFIG,
                **_SCRIPT_NODE_CONFIG,
                **_OBSERVE_NODE_CONFIG,
                **_EXTENSION_NODE_CONFIG,
            })),
            default_value=[],
        ),
        "edges": Field(
            Array(Shape({
                "source_key": Field(String),
                "target_key": Field(String),
            })),
            default_value=[],
        ),
    },
)
def run_pipeline_graph_op(context):
    cfg = context.op_config
    base_url = cfg.get("callback_base_url", "")
    run_id = cfg["run_id"]
    tenant_id = cfg["tenant_id"]
    runtime_params = _runtime_params(cfg)
    iceberg_catalog = cfg.get("iceberg_catalog", "onelake")
    spark_master = os.getenv("SPARK_MASTER_URL", "local[2]")
    max_parallel = max(1, int(cfg.get("max_parallel") or 1))
    nodes = {node["task_key"]: node for node in cfg.get("nodes", [])}
    # 运行时 runConfig 描述任意 DAG；Dagster 仍只有一个固定 op，拓扑调度在 op 内完成。
    downstream = {key: [] for key in nodes}
    indegree = {key: 0 for key in nodes}
    for edge in cfg.get("edges", []):
        source = edge["source_key"]
        target = edge["target_key"]
        if source in nodes and target in nodes:
            downstream[source].append(target)
            indegree[target] += 1

    status = {key: "QUEUED" for key in nodes}
    original_indegree = dict(indegree)
    activated_incoming = {key: 0 for key in nodes}
    lock = threading.Lock()
    process_lock = threading.Lock()
    active_processes = set()
    active_trino_cursors = set()
    cancellation_event = threading.Event()

    def register_process(process):
        with process_lock:
            active_processes.add(process)

    def unregister_process(process):
        with process_lock:
            active_processes.discard(process)

    def register_trino_cursor(cursor):
        with process_lock:
            active_trino_cursors.add(cursor)
        if cancellation_event.is_set():
            try:
                cursor.cancel()
            except Exception:
                pass

    def unregister_trino_cursor(cursor):
        with process_lock:
            active_trino_cursors.discard(cursor)

    def active_process_snapshot(lock_timeout=None):
        if lock_timeout is None:
            with process_lock:
                return list(active_processes)
        acquired = process_lock.acquire(timeout=lock_timeout)
        if not acquired:
            return list(active_processes)
        try:
            return list(active_processes)
        finally:
            process_lock.release()

    def active_trino_cursor_snapshot(lock_timeout=None):
        if lock_timeout is None:
            with process_lock:
                return list(active_trino_cursors)
        acquired = process_lock.acquire(timeout=lock_timeout)
        if not acquired:
            return list(active_trino_cursors)
        try:
            return list(active_trino_cursors)
        finally:
            process_lock.release()

    def terminate_active_processes(reason, lock_timeout=None):
        processes = active_process_snapshot(lock_timeout)
        for process in processes:
            if process.poll() is not None:
                continue
            try:
                context.log.warning(
                    "terminating spark-submit process group pid=%s reason=%s",
                    process.pid,
                    reason,
                )
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                continue
            except Exception as exc:
                context.log.warning("failed to terminate process group pid=%s: %s", process.pid, exc)
        for process in processes:
            if process.poll() is not None:
                continue
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    context.log.warning("force killing spark-submit process group pid=%s", process.pid)
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            except Exception as exc:
                context.log.warning("failed to force kill process group pid=%s: %s", process.pid, exc)

    def terminate_active_trino_queries(reason, lock_timeout=None):
        cancellation_event.set()
        for cursor in active_trino_cursor_snapshot(lock_timeout):
            try:
                context.log.warning("cancelling Trino query reason=%s", reason)
                cursor.cancel()
            except Exception as exc:
                context.log.warning("failed to cancel Trino query: %s", exc)

    def terminate_active_executions(reason, lock_timeout=None):
        terminate_active_processes(reason, lock_timeout)
        terminate_active_trino_queries(reason, lock_timeout)

    previous_signal_handlers = {}

    def handle_termination_signal(signum, frame):
        reason = signal.Signals(signum).name
        terminate_active_executions(reason, lock_timeout=1)
        raise KeyboardInterrupt(reason)

    def install_signal_handlers():
        for signum in (signal.SIGTERM, signal.SIGINT):
            previous_signal_handlers[signum] = signal.getsignal(signum)
            signal.signal(signum, handle_termination_signal)

    def restore_signal_handlers():
        for signum, handler in previous_signal_handlers.items():
            signal.signal(signum, handler)

    def is_execution_interrupt(exc):
        return isinstance(exc, (KeyboardInterrupt, SystemExit)) or (
            exc.__class__.__name__ == "DagsterExecutionInterruptedError"
        )

    def base_attempt(task_key):
        # Java 侧传入跨 run 累计 attempt；普通首跑没有该字段时从 1 开始。
        return max(1, int(nodes[task_key].get("base_attempt", 1) or 1))

    def cumulative_attempt(task_key, local_attempt=1):
        # Dagster op 内自动重试仍从 1 计数，回调给 Java 时转换成累计 attempt。
        return base_attempt(task_key) + max(1, int(local_attempt or 1)) - 1

    def materialize_success(task_key):
        node = nodes[task_key]
        if _materializes_asset(node):
            context.log_event(AssetMaterialization(
                asset_key=node.get("target_fqn") or task_key,
                description=f"pipeline node {task_key}",
            ))

    def mark_downstream_failed(root_key):
        # 失败节点只短路仍未开始的下游；已经运行的并发分支继续完成。
        stack = list(downstream.get(root_key, []))
        while stack:
            task_key = stack.pop()
            with lock:
                if status.get(task_key) != "QUEUED":
                    continue
                status[task_key] = "UPSTREAM_FAILED"
            message = f"upstream failed: {root_key}"
            attempt = base_attempt(task_key)
            # 被短路的节点没有本地重试过程，使用本次重跑的 base_attempt 归档日志与回调。
            log_ref = _upload_log(tenant_id, run_id, task_key, attempt, message, context.log)
            _callback(base_url, run_id, task_key, {
                "status": "UPSTREAM_FAILED",
                "errorMsg": message,
                "finishedAt": _now(),
                "logRef": log_ref,
                "attempt": attempt,
            }, context.log)
            stack.extend(downstream.get(task_key, []))

    def mark_skipped(task_key, reason):
        status[task_key] = "SKIPPED"
        attempt = base_attempt(task_key)
        log_ref = _upload_log(tenant_id, run_id, task_key, attempt, reason, context.log)
        _callback(base_url, run_id, task_key, {
            "status": "SKIPPED",
            "finishedAt": _now(),
            "dagsterStepKey": task_key,
            "logRef": log_ref,
            "attempt": attempt,
        }, context.log)

    def release_downstream(root_key, active_children=None):
        """Release active edges and propagate intentional skips through inactive-only paths."""
        pending = [(root_key, active_children)]
        while pending:
            parent, selected = pending.pop()
            for child in downstream.get(parent, []):
                indegree[child] -= 1
                if selected is None or child in selected:
                    activated_incoming[child] += 1
                if (indegree[child] == 0
                        and status.get(child) == "QUEUED"
                        and original_indegree.get(child, 0) > 0
                        and activated_incoming[child] == 0):
                    mark_skipped(child, f"branch not selected upstream of {child}")
                    pending.append((child, set()))

    def run_node(task_key):
        node = _node_with_runtime_params(nodes[task_key], runtime_params)
        task_type = node["task_type"]
        first_attempt = base_attempt(task_key)
        _callback(base_url, run_id, task_key, {
            "status": "RUNNING",
            "startedAt": _now(),
            "dagsterStepKey": task_key,
            "attempt": first_attempt,
        }, context.log)

        try:
            node = _render_node_config(base_url, run_id, task_key, node)
        except Exception as exc:
            message = str(exc)
            log_ref = _upload_log(tenant_id, run_id, task_key, first_attempt, message, context.log)
            _callback(base_url, run_id, task_key, {
                "status": "FAILED", "finishedAt": _now(), "attempt": first_attempt,
                "dagsterStepKey": task_key, "errorMsg": _tail(message, 3900), "logRef": log_ref,
            }, context.log)
            return "FAILED"

        if task_type in _CONTROL_TASK_TYPES:
            try:
                selected, outputs = _control_targets(node, downstream.get(task_key, []))
                message = (
                    f"{task_type} selected downstream: "
                    + (", ".join(sorted(selected)) if selected else "<none>")
                )
                log_ref = _upload_log(
                    tenant_id, run_id, task_key, first_attempt, message, context.log,
                )
                _callback(base_url, run_id, task_key, {
                    "status": "SUCCEEDED",
                    "finishedAt": _now(),
                    "dagsterStepKey": task_key,
                    "outputs": outputs,
                    "logRef": log_ref,
                    "attempt": first_attempt,
                }, context.log)
                return {"status": "SUCCEEDED", "active_children": selected}
            except Exception as exc:
                message = str(exc)
                log_ref = _upload_log(
                    tenant_id, run_id, task_key, first_attempt, message, context.log,
                )
                _callback(base_url, run_id, task_key, {
                    "status": "FAILED", "finishedAt": _now(), "attempt": first_attempt,
                    "dagsterStepKey": task_key, "errorMsg": _tail(message, 3900),
                    "logRef": log_ref,
                }, context.log)
                return "FAILED"

        if task_type == "SYNC_REF":
            # SYNC_REF 是图内可观测节点但不提交 Spark，同样要用累计 attempt 保持 task_run 单调。
            log_ref = _upload_log(tenant_id, run_id, task_key, first_attempt, "SYNC_REF completed", context.log)
            _callback(base_url, run_id, task_key, {
                "status": "SUCCEEDED",
                "finishedAt": _now(),
                "artifactPath": _table_artifact(node),
                "logRef": log_ref,
                "attempt": first_attempt,
            }, context.log)
            materialize_success(task_key)
            return "SUCCEEDED"

        if task_type in _OBSERVE_WAIT_TASK_TYPES:
            observe_status, _ = _run_observe_wait_with_callback(
                node, runtime_params, base_url, run_id, tenant_id, task_key,
                first_attempt, cancellation_event, context.log,
            )
            return observe_status

        if task_type in _EXTENSION_TASK_TYPES:
            extension_status, _ = _run_extension_with_callback(
                node, base_url, run_id, tenant_id, task_key,
                first_attempt, cancellation_event, context.log,
            )
            return extension_status

        attempts = max(1, int(node.get("max_retries", 0)) + 1)
        last_log = ""
        last_log_ref = ""
        for local_attempt in range(1, attempts + 1):
            attempt = cumulative_attempt(task_key, local_attempt)
            temp_paths = []
            process = None
            try:
                if task_type == "TRINO_SQL":
                    context.log.info(
                        "[%s] attempt %s (local %s/%s) executing with Trino",
                        task_key, attempt, local_attempt, attempts,
                    )
                    result = _execute_trino_sql(
                        node,
                        cancellation_event=cancellation_event,
                        register_cursor=register_trino_cursor,
                        unregister_cursor=unregister_trino_cursor,
                    )
                    cmd = result.args
                elif task_type in _SCRIPT_TASK_TYPES:
                    context.log.info(
                        "[%s] attempt %s (local %s/%s) executing in %s sandbox",
                        task_key, attempt, local_attempt, attempts, task_type,
                    )
                    result = _execute_sandboxed_script(
                        node,
                        register_process=register_process,
                        unregister_process=unregister_process,
                    )
                    cmd = result.args
                else:
                    cmd, temp_paths = _dispatch_graph_node_command(
                        node, iceberg_catalog, spark_master,
                    )
                    context.log.info(
                        "[%s] attempt %s (local %s/%s) spark-submit cmd: %s",
                        task_key,
                        attempt,
                        local_attempt,
                        attempts,
                        " ".join(cmd),
                    )
                    process = subprocess.Popen(
                        cmd,
                        text=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        start_new_session=True,
                    )
                    register_process(process)
                    stdout, stderr = process.communicate()
                    result = subprocess.CompletedProcess(cmd, process.returncode, stdout, stderr)
                last_log = (result.stdout or "") + "\n" + (result.stderr or "")
                if result.stdout:
                    context.log.info(result.stdout)
                if result.stderr:
                    context.log.warning(result.stderr)
                last_log = _spark_log_content(cmd, result)
                last_log_ref = _upload_log(tenant_id, run_id, task_key, attempt, last_log, context.log)
                if result.returncode == 0:
                    _callback(base_url, run_id, task_key,
                              _success_callback_payload(
                                  node, result, finishedAt=_now(), logRef=last_log_ref,
                                  attempt=attempt),
                              context.log)
                    materialize_success(task_key)
                    return "SUCCEEDED"
                context.log.warning(
                    "[%s] attempt %s failed with exit code %s",
                    task_key,
                    attempt,
                    result.returncode,
                )
            except _TrinoExecutionCancelled as exc:
                raise KeyboardInterrupt(str(exc)) from exc
            except _CallbackDeliveryError:
                # Do not run spark-submit again after the data write has succeeded.
                raise
            except Exception as exc:
                last_log = str(exc)
                last_log_ref = _upload_log(tenant_id, run_id, task_key, attempt, last_log, context.log)
                context.log.warning("[%s] attempt %s failed: %s", task_key, attempt, exc)
            finally:
                if process is not None:
                    unregister_process(process)
                for path in temp_paths:
                    try:
                        os.unlink(path)
                    except OSError:
                        pass

        _callback(base_url, run_id, task_key, {
            "status": "FAILED",
            "finishedAt": _now(),
            "errorMsg": _tail(last_log, 3900),
            "logRef": last_log_ref,
            "attempt": cumulative_attempt(task_key, attempts),
        }, context.log)
        return "FAILED"

    failures = []
    install_signal_handlers()
    try:
        with ThreadPoolExecutor(max_workers=max_parallel) as pool:
            futures = {}

            def submit_ready():
                # 就绪即调度：入度归零的 QUEUED 节点立即进入线程池，受 max_parallel 限流。
                with lock:
                    ready = [
                        key for key in nodes
                        if status[key] == "QUEUED" and indegree[key] == 0 and key not in futures
                    ]
                    for key in ready:
                        status[key] = "RUNNING"
                        futures[key] = pool.submit(run_node, key)

            try:
                submit_ready()
                while futures:
                    done_key = None
                    for key, future in list(futures.items()):
                        if future.done():
                            done_key = key
                            break
                    if done_key is None:
                        time.sleep(0.05)
                        continue

                    future = futures.pop(done_key)
                    result = future.result()
                    node_status = result.get("status") if isinstance(result, dict) else result
                    active_children = (
                        result.get("active_children") if isinstance(result, dict) else None
                    )
                    with lock:
                        status[done_key] = node_status
                        if node_status == "SUCCEEDED":
                            release_downstream(done_key, active_children)
                        elif node_status == "SKIPPED":
                            release_downstream(done_key, set())
                        else:
                            failures.append(done_key)
                    if node_status not in {"SUCCEEDED", "SKIPPED"}:
                        mark_downstream_failed(done_key)
                    submit_ready()
            except BaseException as exc:
                if is_execution_interrupt(exc):
                    terminate_active_executions(exc.__class__.__name__)
                raise
    finally:
        restore_signal_handlers()

    blocked = [key for key, value in status.items() if value == "QUEUED"]
    for task_key in blocked:
        status[task_key] = "FAILED"
        message = "pipeline graph could not schedule task"
        attempt = base_attempt(task_key)
        log_ref = _upload_log(tenant_id, run_id, task_key, attempt, message, context.log)
        _callback(base_url, run_id, task_key, {
            "status": "FAILED",
            "errorMsg": message,
            "finishedAt": _now(),
            "logRef": log_ref,
            "attempt": attempt,
        }, context.log)
    failures.extend(blocked)
    if failures:
        raise RuntimeError("pipeline nodes failed: " + ", ".join(failures))
    return {"run_id": run_id, "nodes": list(nodes.keys()), "status": status}


@job(name="onelake_pipeline_graph_run")
def onelake_pipeline_graph_run():
    run_pipeline_graph_op()


# ---------------------------------------------------------------------------
# Notebook op — papermill 参数化执行（P4c）
# 文档参考：docs/数据分析与可视化模块设计方案.md §7.9
# ---------------------------------------------------------------------------


@op(
    name="run_notebook_op",
    config_schema={
        "notebook_path": Field(String, description="MinIO/JupyterHub 上的 .ipynb 路径"),
        "parameters": Field(
            Shape({}),
            default_value={},
            description="papermill 参数化字典（与 Notebook.params_schema 对齐）",
        ),
        "run_id": Field(String, description="analytics.notebook_run.id"),
        "tenant_id": Field(String),
        "kernel_name": Field(String, default_value="python3"),
        "output_dir": Field(String, default_value="/artifacts"),
    },
)
def run_notebook_op(context):
    cfg = context.op_config
    import papermill as pm

    notebook_path = cfg["notebook_path"]
    parameters = cfg.get("parameters") or {}
    run_id = cfg["run_id"]
    kernel_name = cfg.get("kernel_name", "python3")
    output_dir = cfg.get("output_dir", "/artifacts")
    os.makedirs(output_dir, exist_ok=True)

    out_ipynb = os.path.join(output_dir, f"{run_id}.ipynb")
    out_html = os.path.join(output_dir, f"{run_id}.html")

    context.log.info(
        "OneLake papermill run_id=%s tenant=%s notebook=%s kernel=%s",
        run_id, cfg["tenant_id"], notebook_path, kernel_name,
    )

    # papermill 执行；产出 .ipynb（含 outputs）
    pm.execute_notebook(
        notebook_path,
        out_ipynb,
        parameters=parameters,
        kernel_name=kernel_name,
        progress_bar=False,
        log_output=True,
        cwd="/tmp",
    )

    # nbconvert 转 HTML 报告（供前端展示）
    try:
        import subprocess
        subprocess.run(
            ["jupyter", "nbconvert", "--to", "html", "--output", out_html, out_ipynb],
            check=True, timeout=300,
        )
        context.log.info("notebook HTML generated: %s", out_html)
    except Exception as e:
        context.log.warning("nbconvert failed: %s", e)

    return {
        "run_id": run_id,
        "output_ipynb": out_ipynb,
        "output_html": out_html,
    }


@job(name="onelake_notebook_run")
def onelake_notebook_run():
    """Notebook 调度执行 job（papermill）。"""
    run_notebook_op()


@repository(name="onelake")
def onelake_repository():
    jobs = [
        onelake_sync_task_schedule_reconcile,
        onelake_pipeline_run,
        onelake_pipeline_graph_run,
        onelake_notebook_run,
    ]
    # Reload before a GRAPH launch repopulates this list from the Java-owned topology
    # snapshot. Every pipeline task is consequently a visible Dagster step.
    jobs.extend(_load_native_pipeline_jobs())
    return jobs

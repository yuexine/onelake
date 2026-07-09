import os
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from urllib.parse import quote

from dagster import Array, AssetMaterialization, Field, Int, Shape, String, job, op, repository

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
        with tempfile.NamedTemporaryFile(mode="w", suffix=".sql", delete=False) as sql_file:
            sql_file.write(node["sql_or_script"])
            sql_path = sql_file.name
        temp_paths.append(sql_path)
        wrapper = """
import re
import sys
from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("onelake-spark-sql").getOrCreate()
with open(sys.argv[1], "r", encoding="utf-8") as f:
    sql_text = f.read()
statements = [s.strip() for s in sql_text.split(";") if s.strip()]
for statement in statements:
    df = spark.sql(statement)
    if re.match(r"^\\s*(select|show|describe|explain)\\b", statement, re.IGNORECASE):
        df.show(20, truncate=False)
spark.stop()
"""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
            f.write(wrapper)
            script_path = f.name
        temp_paths.append(script_path)
        app_args = [sql_path]

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
        "tasks": Field(
            Array(Shape({
                "task_key": Field(String),
                "task_type": Field(String, description="SPARK_SQL | PYSPARK | QUALITY_GATE"),
                "sql_or_script": Field(String, description="Spark SQL string or PySpark script content"),
                "target_fqn": Field(String, default_value=""),
                "from_tables": Field(Array(String), default_value=[]),
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

        node = dict(task)
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

            _callback(base_url, cfg["run_id"], task["task_key"], {
                "status": "SUCCEEDED",
                "finishedAt": _now(),
                "artifactPath": _table_artifact(task),
                "logRef": log_ref,
                "attempt": 1,
            }, context.log)
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

    target_base_url = (base_url or os.getenv("ONELAKE_CALLBACK_BASE_URL", "")).rstrip("/")
    if not target_base_url:
        log.info("callback skipped run=%s task=%s payload=%s", run_id, task_key, payload)
        return

    token = os.getenv("ONELAKE_INTERNAL_TOKEN", "") or os.getenv("ONELAKE_ORCHESTRATION_INTERNAL_TOKEN", "")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Onelake-Internal-Token"] = token
    url = (
        f"{target_base_url}/api/v1/internal/orchestration/runs/{run_id}"
        f"/tasks/{quote(str(task_key), safe='')}/status"
    )
    try:
        resp = requests.post(url, json=payload, headers=headers, timeout=5)
        resp.raise_for_status()
    except Exception as exc:
        log.warning("callback failed run=%s task=%s url=%s error=%s", run_id, task_key, url, exc)


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


@op(
    name="run_pipeline_graph_op",
    config_schema={
        "pipeline_id": Field(String),
        "run_id": Field(String),
        "tenant_id": Field(String),
        "iceberg_catalog": Field(String, default_value="onelake"),
        "execution_mode": Field(String, default_value="GRAPH"),
        "callback_base_url": Field(String, default_value=""),
        "max_parallel": Field(Int, default_value=4),
        "nodes": Field(
            Array(Shape({
                "task_key": Field(String),
                "task_type": Field(String),
                "sql_or_script": Field(String, default_value=""),
                "target_fqn": Field(String, default_value=""),
                "from_tables": Field(Array(String), default_value=[]),
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
    lock = threading.Lock()

    def base_attempt(task_key):
        # Java 侧传入跨 run 累计 attempt；普通首跑没有该字段时从 1 开始。
        return max(1, int(nodes[task_key].get("base_attempt", 1) or 1))

    def cumulative_attempt(task_key, local_attempt=1):
        # Dagster op 内自动重试仍从 1 计数，回调给 Java 时转换成累计 attempt。
        return base_attempt(task_key) + max(1, int(local_attempt or 1)) - 1

    def materialize_success(task_key):
        node = nodes[task_key]
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

    def run_node(task_key):
        node = nodes[task_key]
        task_type = node["task_type"]
        first_attempt = base_attempt(task_key)
        _callback(base_url, run_id, task_key, {
            "status": "RUNNING",
            "startedAt": _now(),
            "dagsterStepKey": task_key,
            "attempt": first_attempt,
        }, context.log)

        if task_type == "SYNC_REF":
            # SYNC_REF 是图内可观测节点但不提交 Spark，同样要用累计 attempt 保持 task_run 单调。
            log_ref = _upload_log(tenant_id, run_id, task_key, first_attempt, "SYNC_REF completed", context.log)
            materialize_success(task_key)
            _callback(base_url, run_id, task_key, {
                "status": "SUCCEEDED",
                "finishedAt": _now(),
                "artifactPath": _table_artifact(node),
                "logRef": log_ref,
                "attempt": first_attempt,
            }, context.log)
            return "SUCCEEDED"

        attempts = max(1, int(node.get("max_retries", 0)) + 1)
        last_log = ""
        last_log_ref = ""
        for local_attempt in range(1, attempts + 1):
            attempt = cumulative_attempt(task_key, local_attempt)
            temp_paths = []
            try:
                cmd, temp_paths = _build_spark_submit(node, iceberg_catalog, spark_master)
                context.log.info(
                    "[%s] attempt %s (local %s/%s) spark-submit cmd: %s",
                    task_key,
                    attempt,
                    local_attempt,
                    attempts,
                    " ".join(cmd),
                )
                result = subprocess.run(
                    cmd,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                last_log = (result.stdout or "") + "\n" + (result.stderr or "")
                if result.stdout:
                    context.log.info(result.stdout)
                if result.stderr:
                    context.log.warning(result.stderr)
                last_log = _spark_log_content(cmd, result)
                last_log_ref = _upload_log(tenant_id, run_id, task_key, attempt, last_log, context.log)
                if result.returncode == 0:
                    materialize_success(task_key)
                    _callback(base_url, run_id, task_key, {
                        "status": "SUCCEEDED",
                        "finishedAt": _now(),
                        "artifactPath": _table_artifact(node),
                        "logRef": last_log_ref,
                        "attempt": attempt,
                    }, context.log)
                    return "SUCCEEDED"
                context.log.warning(
                    "[%s] attempt %s failed with exit code %s",
                    task_key,
                    attempt,
                    result.returncode,
                )
            except Exception as exc:
                last_log = str(exc)
                last_log_ref = _upload_log(tenant_id, run_id, task_key, attempt, last_log, context.log)
                context.log.warning("[%s] attempt %s failed: %s", task_key, attempt, exc)
            finally:
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
            with lock:
                status[done_key] = result
                if result == "SUCCEEDED":
                    for child in downstream.get(done_key, []):
                        indegree[child] -= 1
                else:
                    failures.append(done_key)
            if result != "SUCCEEDED":
                mark_downstream_failed(done_key)
            submit_ready()

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
    return [
        onelake_sync_task_schedule_reconcile,
        onelake_pipeline_run,
        onelake_pipeline_graph_run,
        onelake_notebook_run,
    ]

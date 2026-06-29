import os
import subprocess

from dagster import Array, Field, Shape, String, job, op, repository

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
    import tempfile
    import os

    cfg = context.op_config
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

        temp_paths = []
        try:
            if task_type in ("PYSPARK", "QUALITY_GATE"):
                with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
                    f.write(task["sql_or_script"])
                    script_path = f.name
                temp_paths.append(script_path)
                app_args = []
            else:
                with tempfile.NamedTemporaryFile(mode="w", suffix=".sql", delete=False) as sql_file:
                    sql_file.write(task["sql_or_script"])
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

            if result.returncode != 0:
                raise RuntimeError(
                    f"spark-submit failed for task {task['task_key']} (exit {result.returncode})"
                )

            results.append({
                "task_key": task["task_key"],
                "task_type": task_type,
                "exit_code": result.returncode,
                "target_fqn": task.get("target_fqn", ""),
            })
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
        onelake_notebook_run,
    ]

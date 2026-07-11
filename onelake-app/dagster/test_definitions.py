import os
import signal
import sys
from types import SimpleNamespace
from collections import Counter

import pytest
from dagster import build_op_context, repository

import definitions


def _node(task_key, task_type="SPARK_SQL", max_retries=0, base_attempt=None):
    node = {
        "task_key": task_key,
        "task_type": task_type,
        "sql_or_script": "SELECT 1",
        "target_fqn": f"onelake.dwd.{task_key}",
        "from_tables": [],
        "resource_profile": {
            "executor_memory": "2g",
            "executor_cores": "2",
            "num_executors": "2",
            "driver_memory": "1g",
        },
        "max_retries": max_retries,
    }
    if base_attempt is not None:
        node["base_attempt"] = base_attempt
    return node


def _edge(source, target):
    return {"source_key": source, "target_key": target}


def _config(nodes, edges, max_parallel=4):
    return {
        "pipeline_id": "pipeline-1",
        "run_id": "run-1",
        "tenant_id": "tenant-1",
        "iceberg_catalog": "onelake",
        "execution_mode": "GRAPH",
        "callback_base_url": "",
        "max_parallel": max_parallel,
        "nodes": nodes,
        "edges": edges,
    }


def _legacy_config(tasks, callback_base_url=""):
    return {
        "pipeline_id": "pipeline-1",
        "run_id": "run-1",
        "tenant_id": "tenant-1",
        "iceberg_catalog": "onelake",
        "callback_base_url": callback_base_url,
        "resource_profile": {
            "executor_memory": "2g",
            "executor_cores": "2",
            "num_executors": "2",
            "driver_memory": "1g",
        },
        "tasks": tasks,
    }


def _native_node_config(task_key, task_type="SPARK_SQL"):
    return {
        "pipeline_id": "00000000-0000-0000-0000-000000000001",
        "run_id": "run-1",
        "tenant_id": "tenant-1",
        "task_key": task_key,
        "task_type": task_type,
        "sql_or_script": "SELECT 1",
        "target_fqn": f"onelake.dwd.{task_key}",
        "from_tables": [],
        "resource_profile": {
            "executor_memory": "2g",
            "executor_cores": "2",
            "num_executors": "2",
            "driver_memory": "1g",
        },
        "base_attempt": 1,
        "max_retries": 0,
        "iceberg_catalog": "onelake",
        "callback_base_url": "",
        "runtime_params": [],
    }


def _command(exit_code, stdout=""):
    script = f"import sys; print({stdout!r}); sys.exit({exit_code})"
    return [sys.executable, "-c", script], []


def _install_callback_collector(monkeypatch):
    callbacks = []

    def callback(base_url, run_id, task_key, payload, log):
        callbacks.append((task_key, dict(payload)))

    monkeypatch.setattr(definitions, "_callback", callback)
    monkeypatch.setattr(definitions, "_upload_log", lambda *args: "log://placeholder")
    return callbacks


def _run_graph(config):
    return definitions.run_pipeline_graph_op(build_op_context(op_config=config))


def _statuses(callbacks, task_key):
    return [payload["status"] for key, payload in callbacks if key == task_key]


class _Log:
    def __init__(self):
        self.infos = []
        self.warnings = []

    def info(self, message, *args):
        self.infos.append(message % args if args else message)

    def warning(self, message, *args):
        self.warnings.append(message % args if args else message)


def test_truncate_keeps_head_and_tail_within_budget():
    content = "A" * 120 + "B" * 240

    truncated = definitions._truncate(content, max_bytes=180)

    assert len(truncated.encode("utf-8")) <= 180
    assert truncated.startswith("A" * 36)
    assert "[log truncated:" in truncated
    assert truncated.endswith("B" * (180 - 36 - len(
        "\n... [log truncated: original_bytes=360 max_bytes=180] ...\n".encode("utf-8")
    )))


def test_upload_log_writes_attempt_and_latest(monkeypatch):
    calls = []
    client_args = {}

    class FakeClient:
        def put_object(self, **kwargs):
            calls.append(kwargs)

    def client(service, **kwargs):
        client_args["service"] = service
        client_args.update(kwargs)
        return FakeClient()

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=client))
    monkeypatch.setenv("ONELAKE_LOG_BUCKET", "logs")
    monkeypatch.setenv("MINIO_ENDPOINT", "http://minio.test:9000")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "ak")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "sk")

    key = definitions._upload_log("tenant-1", "run-1", "spark/task", 2, "hello", _Log())

    assert key == "tenant-1/run-1/spark%2Ftask/latest.log"
    assert client_args["service"] == "s3"
    assert client_args["endpoint_url"] == "http://minio.test:9000"
    assert client_args["aws_access_key_id"] == "ak"
    assert client_args["aws_secret_access_key"] == "sk"
    assert [call["Key"] for call in calls] == [
        "tenant-1/run-1/spark%2Ftask/attempt-2.log",
        "tenant-1/run-1/spark%2Ftask/latest.log",
    ]
    assert all(call["Bucket"] == "logs" for call in calls)
    assert all(call["Body"] == b"hello" for call in calls)


def test_upload_log_returns_empty_when_minio_fails(monkeypatch):
    def client(*args, **kwargs):
        raise RuntimeError("minio down")

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=client))
    log = _Log()

    key = definitions._upload_log("tenant-1", "run-1", "spark", 1, "hello", log)

    assert key == ""
    assert log.warnings


def test_spark_log_content_redacts_credentials():
    result = SimpleNamespace(returncode=0, stdout="ok", stderr="")

    content = definitions._spark_log_content([
        "spark-submit",
        "--conf",
        "spark.hadoop.fs.s3a.access.key=minio",
        "--conf",
        "spark.hadoop.fs.s3a.secret.key=minio12345",
        "--conf",
        "spark.executorEnv.AWS_SECRET_ACCESS_KEY=minio12345",
    ], result)

    assert "minio12345" not in content
    assert "spark.hadoop.fs.s3a.access.key=***REDACTED***" in content
    assert "spark.hadoop.fs.s3a.secret.key=***REDACTED***" in content
    assert "spark.executorEnv.AWS_SECRET_ACCESS_KEY=***REDACTED***" in content


def test_success_callback_normalizes_standard_and_custom_outputs():
    payload = definitions._normalize_callback_payload({
        "status": "SUCCEEDED",
        "rowsWritten": 42,
        "artifactPath": "table:onelake.dwd.orders",
        "outputs": {"partition": "20260709"},
    })

    assert payload["outputs"] == {
        "rowsWritten": 42,
        "artifactPath": "table:onelake.dwd.orders",
        "partition": "20260709",
    }


def test_succeeded_callback_retries_and_requires_backend_ack(monkeypatch):
    calls = []

    def post(*args, **kwargs):
        calls.append((args, kwargs))
        raise RuntimeError("backend unavailable")

    monkeypatch.setitem(sys.modules, "requests", SimpleNamespace(post=post))
    monkeypatch.setattr(definitions.time, "sleep", lambda *_: None)
    log = _Log()

    with pytest.raises(definitions._CallbackDeliveryError, match="not acknowledged"):
        definitions._callback(
            "http://api", "run-1", "extract", {"status": "SUCCEEDED"}, log
        )

    assert len(calls) == 3
    assert len(log.warnings) == 3


def test_succeeded_callback_requires_configured_backend_url(monkeypatch):
    monkeypatch.delenv("ONELAKE_CALLBACK_BASE_URL", raising=False)

    with pytest.raises(definitions._CallbackDeliveryError, match="base URL is empty"):
        definitions._callback(
            "", "run-1", "extract", {"status": "SUCCEEDED"}, _Log()
        )


def test_succeeded_callback_accepts_only_authoritative_succeeded_status(monkeypatch):
    calls = []

    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {"data": {"applied": False, "currentStatus": "FAILED"}}

    def post(*args, **kwargs):
        calls.append((args, kwargs))
        return Response()

    monkeypatch.setitem(sys.modules, "requests", SimpleNamespace(post=post))
    monkeypatch.setattr(definitions.time, "sleep", lambda *_: None)

    with pytest.raises(definitions._CallbackDeliveryError, match="not acknowledged"):
        definitions._callback(
            "http://api", "run-1", "extract", {"status": "SUCCEEDED"}, _Log()
        )

    assert len(calls) == 3


def test_succeeded_callback_accepts_idempotent_succeeded_status(monkeypatch):
    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {"data": {"applied": False, "currentStatus": "SUCCEEDED"}}

    monkeypatch.setitem(
        sys.modules, "requests", SimpleNamespace(post=lambda *args, **kwargs: Response())
    )

    definitions._callback(
        "http://api", "run-1", "extract", {"status": "SUCCEEDED"}, _Log()
    )


def test_extract_task_outputs_uses_last_valid_marker_and_validates_rows_written():
    stdout = """
noise
ONELAKE_OUTPUTS_JSON={not-json}
ONELAKE_OUTPUTS_JSON={"rowsWritten":5,"partition":"20260711"}
"""

    assert definitions._extract_task_outputs(stdout) == {
        "rowsWritten": 5,
        "partition": "20260711",
    }
    with pytest.raises(ValueError, match="non-negative integer"):
        definitions._extract_task_outputs('ONELAKE_OUTPUTS_JSON={"rowsWritten":-1}')


def test_spark_sql_wrapper_reads_snapshot_metrics_without_write_statement_regex():
    node = _node("spark_a")
    node["sql_or_script"] = """
-- transformation header
INSERT INTO onelake.dwd.spark_a SELECT 1;
MERGE INTO onelake.dwd.spark_a t USING source s ON t.id = s.id
WHEN MATCHED THEN UPDATE SET t.value = s.value
"""
    cmd, temp_paths = definitions._build_spark_submit(node, "onelake", "local[2]")
    try:
        wrapper_path = cmd[-3]
        with open(wrapper_path, encoding="utf-8") as wrapper_file:
            wrapper = wrapper_file.read()
        assert definitions._TASK_OUTPUTS_MARKER in wrapper
        assert "summary['added-records']" in wrapper
        assert "summary['spark.app.id']" in wrapper
        assert "spark.catalog.tableExists(target_fqn)" in wrapper
        assert "table_write" not in wrapper
        assert ".count()" not in wrapper
        assert cmd[-1] == "onelake.dwd.spark_a"
    finally:
        for path in temp_paths:
            os.unlink(path)


def test_render_node_config_pulls_final_config_only_when_upstream_is_referenced(monkeypatch):
    calls = []

    class Response:
        ok = True
        text = ""
        reason = "OK"

        def json(self):
            return {"data": {"config": {"sql_or_script": "assert 42 >= 0"}}}

    def post(url, **kwargs):
        calls.append((url, kwargs))
        return Response()

    monkeypatch.setitem(sys.modules, "requests", SimpleNamespace(post=post))
    monkeypatch.setenv("ONELAKE_INTERNAL_TOKEN", "internal-secret")

    untouched = {"sql_or_script": "SELECT 1"}
    assert definitions._render_node_config("http://api", "run-1", "plain", untouched) is untouched
    rendered = definitions._render_node_config(
        "http://api",
        "run-1",
        "quality_gate",
        {
            "sql_or_script": "assert ${upstream.extract.rowsWritten} >= 0",
            "upstream_task_keys": ["extract"],
        },
    )

    assert rendered["sql_or_script"] == "assert 42 >= 0"
    assert len(calls) == 1
    assert calls[0][0].endswith("/runs/run-1/tasks/quality_gate/render-config")
    assert calls[0][1]["headers"]["X-Onelake-Internal-Token"] == "internal-secret"
    assert calls[0][1]["json"]["upstreamTaskKeys"] == ["extract"]


def test_legacy_spark_task_callbacks_log_ref(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    task = _node("spark_a")
    task.pop("max_retries")

    monkeypatch.setattr(definitions, "_build_spark_submit", lambda *args: _command(
        0, 'ONELAKE_OUTPUTS_JSON={"rowsWritten":5,"partition":"20260711"}'
    ))

    result = definitions.run_spark_task_op(
        build_op_context(op_config=_legacy_config([task], callback_base_url="http://api"))
    )

    assert result["tasks"][0]["log_ref"] == "log://placeholder"
    assert _statuses(callbacks, "spark_a") == ["RUNNING", "SUCCEEDED"]
    success_payload = callbacks[-1][1]
    assert success_payload["logRef"] == "log://placeholder"
    assert success_payload["attempt"] == 1
    assert success_payload["artifactPath"] == "table:onelake.dwd.spark_a"
    assert success_payload["rowsWritten"] == 5
    assert success_payload["outputs"] == {"rowsWritten": 5, "partition": "20260711"}


def test_native_pipeline_job_exposes_per_task_steps_and_native_dependencies(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    definition = {
        "pipeline_id": "00000000-0000-0000-0000-000000000001",
        # Purposefully list the downstream task first: persistent UI creation order
        # does not have to match topology order.
        "task_keys": ["quality_gate", "sync_ref", "sync_orders"],
        "edges": [
            {"source_key": "sync_ref", "target_key": "quality_gate"},
            {"source_key": "sync_orders", "target_key": "quality_gate"},
        ],
    }

    pipeline_job = definitions._build_native_pipeline_job(definition)
    result = pipeline_job.execute_in_process(run_config={
        "ops": {
            "sync_ref": {"config": _native_node_config("sync_ref", "SYNC_REF")},
            "sync_orders": {"config": _native_node_config("sync_orders", "SYNC_REF")},
            "quality_gate": {"config": _native_node_config("quality_gate", "SYNC_REF")},
        },
    })

    assert pipeline_job.name == "onelake_pipeline_graph_00000000000000000000000000000001"
    assert result.success
    step_successes = {event.step_key for event in result.all_events if event.is_step_success}
    assert step_successes == {"sync_ref", "sync_orders", "quality_gate"}
    running = [key for key, payload in callbacks if payload["status"] == "RUNNING"]
    assert set(running[:2]) == {"sync_ref", "sync_orders"}
    assert running[-1] == "quality_gate"


def test_native_pipeline_jobs_reuse_same_task_op_definition_across_pipelines():
    original_ops = dict(definitions._NATIVE_TASK_OPS)
    definitions._NATIVE_TASK_OPS.clear()
    try:
        first = definitions._build_native_pipeline_job(
            {"pipeline_id": "00000000-0000-0000-0000-000000000011", "task_keys": ["shared"], "edges": []},
            {"shared": 1},
        )
        second = definitions._build_native_pipeline_job(
            {
                "pipeline_id": "00000000-0000-0000-0000-000000000012",
                "task_keys": ["source", "shared"],
                "edges": [{"source_key": "source", "target_key": "shared"}],
            },
            {"shared": 1, "source": 0},
        )

        @repository
        def combined_repository():
            return [first, second]

        assert {job.name for job in combined_repository.get_all_jobs()} == {
            first.name,
            second.name,
        }
    finally:
        definitions._NATIVE_TASK_OPS.clear()
        definitions._NATIVE_TASK_OPS.update(original_ops)


def test_native_node_forwards_termination_to_spark_process_group(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    killed = []

    class FakeProcess:
        pid = 4321
        returncode = None

        def poll(self):
            return self.returncode

        def wait(self, timeout=None):
            self.returncode = -signal.SIGTERM
            return self.returncode

        def communicate(self):
            signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)

    process = FakeProcess()
    monkeypatch.setattr(definitions, "_build_spark_submit", lambda *args: (["spark-submit"], []))
    monkeypatch.setattr(definitions.subprocess, "Popen", lambda *args, **kwargs: process)

    def killpg(pid, signum):
        killed.append((pid, signum))
        process.returncode = -signum

    monkeypatch.setattr(definitions.os, "killpg", killpg)
    context = SimpleNamespace(
        op_config=_native_node_config("slow_node"),
        log=_Log(),
        log_event=lambda event: None,
    )

    with pytest.raises(KeyboardInterrupt, match="SIGTERM"):
        definitions._execute_native_graph_node(context)

    assert killed == [(4321, signal.SIGTERM)]
    assert _statuses(callbacks, "slow_node") == ["RUNNING"]


def test_native_node_does_not_repeat_spark_write_when_success_callback_is_unacknowledged(monkeypatch):
    submit_calls = []
    materializations = []

    def build(*args):
        submit_calls.append(args)
        return _command(0, 'ONELAKE_OUTPUTS_JSON={"rowsWritten":5}')

    def callback(base_url, run_id, task_key, payload, log):
        if payload["status"] == "SUCCEEDED":
            raise definitions._CallbackDeliveryError("backend unavailable")

    monkeypatch.setattr(definitions, "_build_spark_submit", build)
    monkeypatch.setattr(definitions, "_callback", callback)
    monkeypatch.setattr(definitions, "_upload_log", lambda *args: "log://placeholder")
    config = _native_node_config("spark_write")
    config["max_retries"] = 2
    context = SimpleNamespace(
        op_config=config,
        log=_Log(),
        log_event=materializations.append,
    )

    with pytest.raises(definitions._CallbackDeliveryError, match="backend unavailable"):
        definitions._execute_native_graph_node(context)

    assert len(submit_calls) == 1
    assert materializations == []


def test_graph_linear_order_and_sync_ref(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    submit_calls = []

    def build(node, iceberg_catalog, spark_master):
        submit_calls.append(node["task_key"])
        return _command(0)

    monkeypatch.setattr(definitions, "_build_spark_submit", build)

    result = _run_graph(_config(
        [_node("sync", "SYNC_REF"), _node("spark_a"), _node("spark_b")],
        [_edge("sync", "spark_a"), _edge("spark_a", "spark_b")],
        max_parallel=2,
    ))

    assert result["status"] == {
        "sync": "SUCCEEDED",
        "spark_a": "SUCCEEDED",
        "spark_b": "SUCCEEDED",
    }
    assert submit_calls == ["spark_a", "spark_b"]
    events = [(key, payload["status"]) for key, payload in callbacks]
    assert events.index(("sync", "SUCCEEDED")) < events.index(("spark_a", "RUNNING"))
    assert events.index(("spark_a", "SUCCEEDED")) < events.index(("spark_b", "RUNNING"))


def test_graph_failure_short_circuits_downstream(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    submit_calls = []

    def build(node, iceberg_catalog, spark_master):
        submit_calls.append(node["task_key"])
        return _command(1 if node["task_key"] == "middle" else 0)

    monkeypatch.setattr(definitions, "_build_spark_submit", build)

    with pytest.raises(RuntimeError, match="middle"):
        _run_graph(_config(
            [_node("start"), _node("middle"), _node("end")],
            [_edge("start", "middle"), _edge("middle", "end")],
            max_parallel=2,
        ))

    assert submit_calls == ["start", "middle"]
    assert _statuses(callbacks, "middle")[-1] == "FAILED"
    assert _statuses(callbacks, "end") == ["UPSTREAM_FAILED"]


def test_graph_diamond_keeps_independent_branch_running(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    submit_calls = []

    def build(node, iceberg_catalog, spark_master):
        submit_calls.append(node["task_key"])
        return _command(1 if node["task_key"] == "left" else 0)

    monkeypatch.setattr(definitions, "_build_spark_submit", build)

    with pytest.raises(RuntimeError, match="left"):
        _run_graph(_config(
            [_node("root"), _node("left"), _node("right"), _node("join")],
            [
                _edge("root", "left"),
                _edge("root", "right"),
                _edge("left", "join"),
                _edge("right", "join"),
            ],
            max_parallel=2,
        ))

    assert Counter(submit_calls) == Counter(["root", "left", "right"])
    assert _statuses(callbacks, "right")[-1] == "SUCCEEDED"
    assert _statuses(callbacks, "join") == ["UPSTREAM_FAILED"]


def test_graph_retries_node_until_success(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    attempts = Counter()

    def build(node, iceberg_catalog, spark_master):
        attempts[node["task_key"]] += 1
        return _command(1 if attempts[node["task_key"]] == 1 else 0)

    monkeypatch.setattr(definitions, "_build_spark_submit", build)

    result = _run_graph(_config([_node("retry_me", max_retries=1)], [], max_parallel=1))

    assert result["status"]["retry_me"] == "SUCCEEDED"
    assert attempts["retry_me"] == 2
    success_payloads = [
        payload for key, payload in callbacks
        if key == "retry_me" and payload["status"] == "SUCCEEDED"
    ]
    assert success_payloads[-1]["attempt"] == 2


def test_graph_base_attempt_offsets_retry_callbacks(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    attempts = Counter()

    def build(node, iceberg_catalog, spark_master):
        attempts[node["task_key"]] += 1
        return _command(1 if attempts[node["task_key"]] == 1 else 0)

    monkeypatch.setattr(definitions, "_build_spark_submit", build)

    result = _run_graph(_config([_node("retry_me", max_retries=1, base_attempt=3)], [], max_parallel=1))

    assert result["status"]["retry_me"] == "SUCCEEDED"
    assert [
        payload["attempt"] for key, payload in callbacks
        if key == "retry_me" and "attempt" in payload
    ] == [3, 4]


def test_graph_base_attempt_offsets_terminal_failure(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)

    monkeypatch.setattr(definitions, "_build_spark_submit", lambda *args: _command(1))

    with pytest.raises(RuntimeError, match="fail_me"):
        _run_graph(_config([_node("fail_me", max_retries=1, base_attempt=5)], [], max_parallel=1))

    failed_payloads = [
        payload for key, payload in callbacks
        if key == "fail_me" and payload["status"] == "FAILED"
    ]
    assert failed_payloads[-1]["attempt"] == 6

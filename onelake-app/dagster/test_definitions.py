import sys
from types import SimpleNamespace
from collections import Counter

import pytest
from dagster import build_op_context

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


def _command(exit_code):
    return [sys.executable, "-c", f"import sys; sys.exit({exit_code})"], []


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


def test_legacy_spark_task_callbacks_log_ref(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    task = _node("spark_a")
    task.pop("max_retries")

    monkeypatch.setattr(definitions, "_build_spark_submit", lambda *args: _command(0))

    result = definitions.run_spark_task_op(
        build_op_context(op_config=_legacy_config([task], callback_base_url="http://api"))
    )

    assert result["tasks"][0]["log_ref"] == "log://placeholder"
    assert _statuses(callbacks, "spark_a") == ["RUNNING", "SUCCEEDED"]
    success_payload = callbacks[-1][1]
    assert success_payload["logRef"] == "log://placeholder"
    assert success_payload["attempt"] == 1
    assert success_payload["artifactPath"] == "table:onelake.dwd.spark_a"


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

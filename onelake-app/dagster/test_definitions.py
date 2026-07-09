import sys
from collections import Counter

import pytest
from dagster import build_op_context

import definitions


def _node(task_key, task_type="SPARK_SQL", max_retries=0):
    return {
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

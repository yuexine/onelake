import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
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
    if task_type == "TRINO_SQL":
        node.update({"engine": "TRINO", "catalog": "iceberg", "schema": "dwd"})
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


def _trino_result(rows_written=None):
    outputs = {"engine": "TRINO", "queryId": "query-1"}
    if rows_written is not None:
        outputs["rowsWritten"] = rows_written
    stdout = "trino ok\n" + definitions._TASK_OUTPUTS_MARKER + json.dumps(outputs)
    return subprocess.CompletedProcess(
        ["trino", "trino:8080", "iceberg", "dwd"], 0, stdout, "",
    )


def _direct_script_command(
        task_type, sandbox_binary, workdir, tmpdir, script_name, env, limits):
    script_path = os.path.join(workdir, script_name)
    interpreter = sys.executable if task_type == "PYTHON" else "/bin/sh"
    return [interpreter, script_path]


def _enable_direct_script_sandbox(monkeypatch):
    monkeypatch.setattr(definitions.shutil, "which", lambda *_: "/usr/bin/bwrap")
    monkeypatch.setattr(definitions, "_script_sandbox_command", _direct_script_command)
    monkeypatch.setattr(definitions, "_script_preexec", lambda limits: None)
    monkeypatch.setattr(definitions, "_script_resource_accounting_available", lambda: True)
    monkeypatch.setattr(
        definitions,
        "_script_process_tree_usage",
        lambda pid: {"cpu_seconds": 0, "memory_bytes": 0, "processes": 1},
    )


def _script_node(task_key="script", task_type="PYTHON", script="print('ok')", **limits):
    node = _node(task_key, task_type)
    node.update({
        "engine": "SCRIPT",
        "sql_or_script": script,
        "target_fqn": "",
        "timeout_seconds": 5,
        "cpu_seconds": 5,
        "cpu_cores": 1,
        "memory_mb": 256,
        "max_processes": 8,
        "max_files": 256,
        "file_max_bytes": 1024 * 1024,
        "stdout_max_bytes": 256 * 1024,
        "stderr_max_bytes": 256 * 1024,
        "env": [],
        "network_allowlist": [],
    })
    node.update(limits)
    return node


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


def test_execute_trino_sql_uses_environment_and_reports_query_output(monkeypatch):
    connect_args = {}
    batches = iter([[(42,)], []])

    class Cursor:
        description = [("answer",)]
        stats = {"queryId": "20260712_000001_00001_test"}
        update_count = None

        def execute(self, sql):
            connect_args["sql"] = sql

        def fetchmany(self, size):
            connect_args["fetch_size"] = size
            return next(batches)

        def close(self):
            connect_args["cursor_closed"] = True

    class Connection:
        def cursor(self):
            return Cursor()

        def close(self):
            connect_args["connection_closed"] = True

    def connect(**kwargs):
        connect_args.update(kwargs)
        return Connection()

    monkeypatch.setitem(sys.modules, "trino", SimpleNamespace())
    monkeypatch.setitem(sys.modules, "trino.dbapi", SimpleNamespace(connect=connect))
    monkeypatch.setenv("TRINO_HOST", "trino.test")
    monkeypatch.setenv("TRINO_PORT", "18080")
    monkeypatch.setenv("TRINO_USER", "pipeline")

    result = definitions._execute_trino_sql(_node("validate", "TRINO_SQL"))

    assert result.returncode == 0
    assert connect_args["host"] == "trino.test"
    assert connect_args["port"] == 18080
    assert connect_args["user"] == "pipeline"
    assert connect_args["catalog"] == "iceberg"
    assert connect_args["schema"] == "dwd"
    assert connect_args["sql"] == "SELECT 1"
    assert "[rows] [[42]]" in result.stdout
    assert definitions._extract_task_outputs(result.stdout) == {
        "engine": "TRINO",
        "resultRows": 1,
        "queryId": "20260712_000001_00001_test",
    }
    assert connect_args["cursor_closed"]
    assert connect_args["connection_closed"]


def test_execute_trino_sql_honors_cancellation_before_connect(monkeypatch):
    connect_calls = []
    monkeypatch.setitem(sys.modules, "trino", SimpleNamespace())
    monkeypatch.setitem(
        sys.modules,
        "trino.dbapi",
        SimpleNamespace(connect=lambda **kwargs: connect_calls.append(kwargs)),
    )
    cancellation_event = threading.Event()
    cancellation_event.set()

    with pytest.raises(definitions._TrinoExecutionCancelled, match="before connection"):
        definitions._execute_trino_sql(
            _node("cancelled", "TRINO_SQL"),
            cancellation_event=cancellation_event,
        )

    assert connect_calls == []


def test_execute_trino_sql_normalizes_ctas_result_row_to_rows_written(monkeypatch):
    batches = iter([[(7,)], []])

    class Cursor:
        description = [("rows",)]
        stats = {"queryId": "ctas-query"}
        update_count = None

        def execute(self, sql):
            return None

        def fetchmany(self, size):
            return next(batches)

        def close(self):
            return None

    class Connection:
        def cursor(self):
            return Cursor()

        def close(self):
            return None

    monkeypatch.setitem(sys.modules, "trino", SimpleNamespace())
    monkeypatch.setitem(
        sys.modules,
        "trino.dbapi",
        SimpleNamespace(connect=lambda **kwargs: Connection()),
    )
    node = _node("ctas", "TRINO_SQL")
    node["sql_or_script"] = "CREATE TABLE iceberg.dwd.ctas AS SELECT 1"

    result = definitions._execute_trino_sql(node)

    assert definitions._extract_task_outputs(result.stdout)["rowsWritten"] == 7


def test_execute_trino_sql_drains_results_beyond_log_sample(monkeypatch):
    batches = iter([[(1,), (2,)], [(3,)], []])

    class Cursor:
        description = [("value",)]
        stats = {"queryId": "drained-query"}
        update_count = None

        def execute(self, sql):
            return None

        def fetchmany(self, size):
            return next(batches)

        def close(self):
            return None

    class Connection:
        def cursor(self):
            return Cursor()

        def close(self):
            return None

    monkeypatch.setitem(sys.modules, "trino", SimpleNamespace())
    monkeypatch.setitem(
        sys.modules,
        "trino.dbapi",
        SimpleNamespace(connect=lambda **kwargs: Connection()),
    )
    monkeypatch.setenv("TRINO_LOG_MAX_ROWS", "2")

    result = definitions._execute_trino_sql(_node("drain", "TRINO_SQL"))

    assert definitions._extract_task_outputs(result.stdout)["resultRows"] == 3
    assert "[rows] [[1],[2]]" in result.stdout
    assert "[rows_truncated] bounded to 2 rows / 262144 bytes" in result.stdout


def test_execute_trino_sql_bounds_large_cell_log_bytes(monkeypatch):
    large_value = "x" * 1_000_000
    batches = iter([[(large_value,)], []])

    class Cursor:
        description = [("large_value",)]
        stats = {"queryId": "bounded-query"}
        update_count = None

        def execute(self, sql):
            return None

        def fetchmany(self, size):
            return next(batches)

        def close(self):
            return None

    class Connection:
        def cursor(self):
            return Cursor()

        def close(self):
            return None

    monkeypatch.setitem(sys.modules, "trino", SimpleNamespace())
    monkeypatch.setitem(
        sys.modules,
        "trino.dbapi",
        SimpleNamespace(connect=lambda **kwargs: Connection()),
    )
    monkeypatch.setenv("TRINO_LOG_MAX_BYTES", "1024")

    result = definitions._execute_trino_sql(_node("bounded", "TRINO_SQL"))

    assert definitions._extract_task_outputs(result.stdout)["resultRows"] == 1
    assert len(result.stdout.encode("utf-8")) < 4096
    rows_line = next(line for line in result.stdout.splitlines() if line.startswith("[rows] "))
    assert len(rows_line.removeprefix("[rows] ").encode("utf-8")) <= 1024
    assert "value truncated" in result.stdout
    assert "[rows_truncated] bounded to 100 rows / 1024 bytes" in result.stdout
    assert large_value not in result.stdout


def test_script_sandbox_command_has_private_filesystem_environment_and_network():
    limits = definitions._effective_script_limits(definitions._SCRIPT_DEFAULTS)
    command = definitions._script_sandbox_command(
        "PYTHON",
        "/usr/bin/bwrap",
        "/tmp/work",
        "/tmp/sandbox-tmp",
        "main.py",
        {"PATH": "/usr/bin", "HOME": "/workspace"},
        limits,
    )

    assert command[:7] == [
        "/usr/bin/bwrap", "--die-with-parent", "--unshare-all",
        "--cap-drop", "ALL", "--new-session", "--clearenv",
    ]
    workspace_bind_index = command.index("/tmp/work") - 1
    assert command[workspace_bind_index:workspace_bind_index + 3] == [
        "--bind", "/tmp/work", "/workspace",
    ]
    tmp_bind_index = command.index("/tmp/sandbox-tmp") - 1
    assert command[tmp_bind_index:tmp_bind_index + 3] == [
        "--bind", "/tmp/sandbox-tmp", "/tmp",
    ]
    assert "--tmpfs" not in command
    assert "/opt/dagster" not in command
    assert "/etc" not in command
    assert "/proc" not in command
    assert "/usr/bin/prlimit" in command
    assert "--nproc=4:4" in command
    assert "--cpu=7:7" in command
    assert "--as=67108864:67108864" in command
    assert command[-2:] == ["/usr/local/bin/python3", "/workspace/main.py"]


def test_sandboxed_script_timeout_kills_process_group(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)
    started = time.monotonic()

    result = definitions._execute_sandboxed_script(_script_node(
        "timeout", script="import time\ntime.sleep(3)", timeout_seconds=1, cpu_seconds=1,
    ))

    assert time.monotonic() - started < 3
    assert result.returncode == 124
    assert "[sandbox timeout] exceeded 1 seconds" in result.stderr
    assert result.task_outputs["engine"] == "PYTHON"


def test_sandboxed_script_marks_system_error_truncation(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)

    result = definitions._execute_sandboxed_script(_script_node(
        "timeout_with_full_stderr",
        script="import os, time\nos.write(2, b'x' * 4096)\ntime.sleep(3)",
        timeout_seconds=1,
        cpu_seconds=1,
        stderr_max_bytes=4096,
    ))

    assert result.returncode == 124
    assert len(result.stderr.encode("utf-8")) <= 4096
    assert "sandbox timeout" in result.stderr
    assert result.task_outputs["stderrTruncated"] is True


def test_process_tree_usage_excludes_bubblewrap_and_prlimit_helpers(monkeypatch):
    stats = {
        100: {"comm": "bwrap", "ppid": 1, "utime": 0, "stime": 0,
              "cutime": 0, "cstime": 0, "rss_pages": 1},
        101: {"comm": "bwrap", "ppid": 100, "utime": 0, "stime": 0,
              "cutime": 0, "cstime": 0, "rss_pages": 1},
        102: {"comm": "python3", "ppid": 101, "utime": 1, "stime": 0,
              "cutime": 0, "cstime": 0, "rss_pages": 1},
        103: {"comm": "python3", "ppid": 102, "utime": 1, "stime": 0,
              "cutime": 0, "cstime": 0, "rss_pages": 1},
        104: {"comm": "prlimit", "ppid": 101, "utime": 0, "stime": 0,
              "cutime": 0, "cstime": 0, "rss_pages": 1},
    }
    monkeypatch.setattr(definitions.os, "listdir", lambda path: list(map(str, stats)))
    monkeypatch.setattr(definitions, "_read_process_stat", stats.get)

    usage = definitions._script_process_tree_usage(100)

    assert usage["processes"] == 2


def test_sandboxed_script_truncates_large_stdout_without_pipe_deadlock(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)

    result = definitions._execute_sandboxed_script(_script_node(
        "large_output",
        script="import sys\nsys.stdout.write('x' * 200000)",
        stdout_max_bytes=4096,
    ))

    assert result.returncode == 0
    assert len(result.stdout.encode("utf-8")) <= 4096
    assert "sandbox output truncated" in result.stdout
    assert result.task_outputs == {
        "engine": "PYTHON",
        "stdoutTruncated": True,
        "stderrTruncated": False,
        "effectiveMaxProcesses": 4,
    }


def test_sandboxed_script_binary_output_stays_within_byte_limit(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)

    result = definitions._execute_sandboxed_script(_script_node(
        "binary_output",
        script="import sys\nsys.stdout.buffer.write(b'ok' + b'\\xff' * 8192)",
        stdout_max_bytes=4096,
    ))

    assert result.returncode == 0
    assert len(result.stdout.encode("utf-8")) <= 4096
    assert result.stdout.startswith("ok")
    assert "sandbox output truncated" in result.stdout


def test_sandboxed_script_marks_utf8_normalization_truncation(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)

    result = definitions._execute_sandboxed_script(_script_node(
        "invalid_utf8",
        script="import sys\nsys.stdout.buffer.write(b'\\xff' * 4096)",
        stdout_max_bytes=4096,
    ))

    assert result.returncode == 0
    assert len(result.stdout.encode("utf-8")) <= 4096
    assert result.task_outputs["stdoutTruncated"] is True


def test_sandboxed_script_limits_workspace_entries(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)

    result = definitions._execute_sandboxed_script(_script_node(
        "many_files",
        script="\n".join([
            "for index in range(300):",
            "    open(f'empty-{index}', 'w').close()",
        ]),
        max_files=256,
    ))

    assert result.returncode == definitions._SCRIPT_RESOURCE_EXIT_CODE
    assert "workspace entries exceeded 256" in result.stderr


def test_sandboxed_script_counts_directories_toward_workspace_entries(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)

    result = definitions._execute_sandboxed_script(_script_node(
        "many_directories",
        script="import os\nfor index in range(40): os.mkdir(f'dir-{index}')",
        max_files=16,
    ))

    assert result.returncode == definitions._SCRIPT_RESOURCE_EXIT_CODE
    assert "workspace entries exceeded 16" in result.stderr


def test_workspace_usage_counts_separate_sandbox_tmp_directory(tmp_path):
    workspace = tmp_path / "workspace"
    sandbox_tmp = tmp_path / "tmp"
    workspace.mkdir()
    sandbox_tmp.mkdir()
    script = workspace / "main.py"
    script.write_text("print('ok')")
    for index in range(5):
        (sandbox_tmp / f"temp-{index}").touch()

    usage = definitions._script_workspace_usage(
        (str(workspace), str(sandbox_tmp)),
        str(script),
        {"max_files": 3, "file_max_bytes": 1024},
    )

    assert usage["entries"] == 4


def test_sandboxed_script_kills_aggregate_memory_violation(monkeypatch):
    _enable_direct_script_sandbox(monkeypatch)
    monkeypatch.setattr(
        definitions,
        "_script_process_tree_usage",
        lambda pid: {
            "cpu_seconds": 0,
            "memory_bytes": 65 * 1024 * 1024,
            "processes": 2,
        },
    )
    started = time.monotonic()

    result = definitions._execute_sandboxed_script(_script_node(
        "memory_limit",
        script="import time\ntime.sleep(3)",
        memory_mb=64,
    ))

    assert time.monotonic() - started < 3
    assert result.returncode == definitions._SCRIPT_RESOURCE_EXIT_CODE
    assert "[sandbox resource limit] memory exceeded" in result.stderr
    assert "memory exceeded" in result.task_outputs["resourceLimit"]


def test_script_security_profiles_restrict_syscalls_and_trino_catalogs():
    dagster_dir = Path(__file__).resolve().parent
    seccomp = json.loads((dagster_dir / "seccomp-user-code.json").read_text())
    denied_syscalls = {
        name
        for rule in seccomp["syscalls"]
        if rule["action"] == "SCMP_ACT_ERRNO"
        for name in rule["names"]
    }
    assert seccomp["defaultAction"] == "SCMP_ACT_ALLOW"
    assert {"bpf", "ptrace", "process_vm_readv", "process_vm_writev"} <= denied_syscalls
    assert "unshare" not in denied_syscalls

    trino_rules = json.loads(
        (dagster_dir.parent / "trino" / "access-control-rules.json").read_text()
    )
    pipeline_catalogs = [
        rule for rule in trino_rules["catalogs"]
        if rule.get("user") == "onelake_pipeline"
    ]
    assert pipeline_catalogs == [
        {"user": "onelake_pipeline", "catalog": "iceberg", "allow": "all"},
        {"user": "onelake_pipeline", "catalog": ".*", "allow": "none"},
    ]
    assert any(
        rule.get("user") == "onelake_pipeline" and rule.get("privileges") == []
        for rule in trino_rules["tables"]
    )


def test_sandbox_environment_rejects_control_plane_credentials():
    with pytest.raises(ValueError, match="reserved"):
        definitions._script_environment({"env": [
            {"key": "ONELAKE_INTERNAL_TOKEN", "value": "must-not-leak"},
        ]})


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


def test_native_pipeline_job_name_isolated_by_immutable_version():
    pipeline_job = definitions._build_native_pipeline_job({
        "pipeline_id": "00000000-0000-0000-0000-000000000001",
        "version_id": "00000000-0000-0000-0000-000000000099",
        "task_keys": ["versioned_task"],
        "edges": [],
    })

    assert pipeline_job.name == (
        "onelake_pipeline_graph_00000000000000000000000000000001"
        "_v_00000000000000000000000000000099"
    )


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


def test_native_task_op_cache_expands_when_fan_in_grows():
    original_ops = dict(definitions._NATIVE_TASK_OPS)
    definitions._NATIVE_TASK_OPS.clear()
    try:
        narrow = definitions._make_native_task_op("shared_fan_in", 1)
        wide = definitions._make_native_task_op("shared_fan_in", 3)
        reused = definitions._make_native_task_op("shared_fan_in", 2)

        assert len(narrow.input_defs) == 1
        assert len(wide.input_defs) == 3
        assert reused is wide
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


@pytest.mark.parametrize("task_type", [
    "BRANCH", "CONDITION", "SENSOR", "WAIT",
    "SUB_PIPELINE", "NOTIFY", "ASSERTION",
])
def test_graph_extension_dispatch_stubs_never_call_spark_submit(monkeypatch, task_type):
    def fail_if_called(*args):
        raise AssertionError("extension dispatch must not invoke spark-submit")

    monkeypatch.setattr(definitions, "_build_spark_submit", fail_if_called)

    with pytest.raises(NotImplementedError, match=f"{task_type} graph dispatcher"):
        definitions._dispatch_graph_node_command(
            _node("extension", task_type), "onelake", "local[2]",
        )


@pytest.mark.parametrize("task_type", ["SPARK_SQL", "PYSPARK", "QUALITY_GATE"])
def test_graph_existing_spark_backed_types_keep_submit_dispatch(monkeypatch, task_type):
    calls = []
    monkeypatch.setattr(
        definitions,
        "_build_spark_submit",
        lambda *args: calls.append(args) or (["spark-submit"], []),
    )

    command = definitions._dispatch_graph_node_command(
        _node("existing", task_type), "onelake", "local[2]",
    )

    assert command == (["spark-submit"], [])
    assert len(calls) == 1


def test_graph_trino_node_uses_callback_log_retry_and_releases_downstream(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    attempts = Counter()
    spark_calls = []

    def execute(node, **kwargs):
        attempts[node["task_key"]] += 1
        if node["task_key"] == "trino_ctas" and attempts[node["task_key"]] == 1:
            raise RuntimeError("temporary Trino failure")
        return _trino_result(rows_written=3 if node["task_key"] == "trino_ctas" else None)

    monkeypatch.setattr(definitions, "_execute_trino_sql", execute)
    monkeypatch.setattr(
        definitions,
        "_build_spark_submit",
        lambda *args: spark_calls.append(args) or _command(0),
    )
    trino = _node("trino_ctas", "TRINO_SQL", max_retries=1)
    trino["sql_or_script"] = "CREATE TABLE iceberg.dwd.trino_ctas AS SELECT 1"
    spark = _node("spark_after")

    result = _run_graph(_config([trino, spark], [_edge("trino_ctas", "spark_after")]))

    assert result["status"] == {"trino_ctas": "SUCCEEDED", "spark_after": "SUCCEEDED"}
    assert attempts["trino_ctas"] == 2
    assert len(spark_calls) == 1
    assert _statuses(callbacks, "trino_ctas") == ["RUNNING", "SUCCEEDED"]
    success = [payload for key, payload in callbacks
               if key == "trino_ctas" and payload["status"] == "SUCCEEDED"][0]
    assert success["rowsWritten"] == 3
    assert success["outputs"]["engine"] == "TRINO"


@pytest.mark.parametrize("task_type", ["PYTHON", "SHELL"])
def test_graph_script_node_uses_sandbox_callback_and_minio_log(monkeypatch, task_type):
    callbacks = _install_callback_collector(monkeypatch)
    uploads = []

    def execute(node, **kwargs):
        result = subprocess.CompletedProcess(
            ["sandbox", task_type.lower()], 0, "sandbox output", "",
        )
        result.task_outputs = {
            "engine": task_type,
            "stdoutTruncated": False,
            "stderrTruncated": False,
        }
        return result

    monkeypatch.setattr(definitions, "_execute_sandboxed_script", execute)
    monkeypatch.setattr(
        definitions,
        "_upload_log",
        lambda tenant, run, task, attempt, content, log:
            uploads.append((task, content)) or "minio://script.log",
    )

    result = _run_graph(_config([_script_node("script", task_type)], []))

    assert result["status"] == {"script": "SUCCEEDED"}
    assert _statuses(callbacks, "script") == ["RUNNING", "SUCCEEDED"]
    success = callbacks[-1][1]
    assert success["logRef"] == "minio://script.log"
    assert success["outputs"]["engine"] == task_type
    assert uploads[0][0] == "script"
    assert "sandbox output" in uploads[0][1]


def test_native_trino_node_does_not_invoke_spark_submit(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    materializations = []
    monkeypatch.setattr(
        definitions, "_execute_trino_sql", lambda node, **kwargs: _trino_result())
    monkeypatch.setattr(
        definitions,
        "_build_spark_submit",
        lambda *args: (_ for _ in ()).throw(AssertionError("Trino must not invoke spark-submit")),
    )
    config = _native_node_config("trino_validate", "TRINO_SQL")
    config.update({"engine": "TRINO", "catalog": "iceberg", "schema": "dwd"})
    context = SimpleNamespace(op_config=config, log=_Log(), log_event=materializations.append)

    definitions._execute_native_graph_node(context)

    assert _statuses(callbacks, "trino_validate") == ["RUNNING", "SUCCEEDED"]
    assert materializations == []


def test_native_trino_ctas_emits_target_materialization(monkeypatch):
    _install_callback_collector(monkeypatch)
    materializations = []
    monkeypatch.setattr(
        definitions, "_execute_trino_sql", lambda node, **kwargs: _trino_result(1))
    config = _native_node_config("trino_ctas", "TRINO_SQL")
    config.update({
        "engine": "TRINO",
        "catalog": "iceberg",
        "schema": "dwd",
        "target_fqn": "iceberg.dwd.trino_ctas",
        "sql_or_script": "CREATE TABLE iceberg.dwd.trino_ctas AS SELECT 1",
    })
    context = SimpleNamespace(op_config=config, log=_Log(), log_event=materializations.append)

    definitions._execute_native_graph_node(context)

    assert len(materializations) == 1


def test_graph_termination_cancels_active_trino_cursor(monkeypatch):
    callbacks = _install_callback_collector(monkeypatch)
    cancelled = []

    class Cursor:
        description = None

        def execute(self, sql):
            signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)

        def cancel(self):
            cancelled.append(True)

        def close(self):
            pass

    class Connection:
        def cursor(self):
            return Cursor()

        def close(self):
            pass

    monkeypatch.setitem(sys.modules, "trino", SimpleNamespace())
    monkeypatch.setitem(
        sys.modules,
        "trino.dbapi",
        SimpleNamespace(connect=lambda **kwargs: Connection()),
    )

    with pytest.raises(KeyboardInterrupt, match="SIGTERM"):
        _run_graph(_config([_node("slow_trino", "TRINO_SQL")], []))

    assert cancelled == [True]
    assert _statuses(callbacks, "slow_trino") == ["RUNNING"]


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

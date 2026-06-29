/**
 * Pipeline editor shared state and helpers.
 *
 * <p>Single source of truth for the currently-open pipeline (P2 — Unified Pipeline Editor).
 * See docs/流水线模块重设计方案.md §4.2 (component layering).
 *
 * <p>P6-A: auto-polls task_run status every 5s while any run is non-terminal.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { App as AntApp } from 'antd';
import { PipelineAPI, OrchestrationAPI } from '../../../api';
import type {
  Pipeline,
  PipelineTask,
  PipelineTaskEdge,
  PipelineTaskEdgeRequest,
  PipelineTaskRequest,
  PipelineTaskType,
  PipelineValidationResult,
  TaskRun,
  JobRun,
} from '../../../types';

export interface PipelineEditorState {
  loading: boolean;
  pipeline?: Pipeline;
  tasks: PipelineTask[];
  edges: PipelineTaskEdge[];
  selectedTaskKey?: string;
  validation?: PipelineValidationResult;
  saving: boolean;
}

export function usePipelineEditor(dagId: string | undefined) {
  const { message } = AntApp.useApp();
  const [loading, setLoading] = useState(true);
  const [pipeline, setPipeline] = useState<Pipeline | undefined>(undefined);
  const [tasks, setTasks] = useState<PipelineTask[]>([]);
  const [edges, setEdges] = useState<PipelineTaskEdge[]>([]);
  const [selectedTaskKey, setSelectedTaskKey] = useState<string | undefined>(undefined);
  const [validation, setValidation] = useState<PipelineValidationResult | undefined>(undefined);
  const [saving, setSaving] = useState(false);

  // P6-A: live run state (auto-poll)
  const [latestRun, setLatestRun] = useState<JobRun | undefined>(undefined);
  const [taskRuns, setTaskRuns] = useState<TaskRun[]>([]);
  const [activeRunId, setActiveRunId] = useState<string | undefined>(undefined);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const loadAll = useCallback(async () => {
    if (!dagId) {
      setLoading(false);
      setPipeline(undefined);
      setTasks([]);
      setEdges([]);
      setSelectedTaskKey(undefined);
      setValidation(undefined);
      return;
    }
    setLoading(true);
    try {
      const [p, ts, es] = await Promise.all([
        PipelineAPI.get(dagId),
        PipelineAPI.listTasks(dagId),
        PipelineAPI.listEdges(dagId),
      ]);
      setPipeline(p);
      setTasks(ts);
      setEdges(es);
    } catch (err) {
      message.error(`加载流水线失败: ${(err as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [dagId, message]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const selectedTask = useMemo(
    () => tasks.find((t) => t.taskKey === selectedTaskKey),
    [tasks, selectedTaskKey],
  );

  const createTask = useCallback(
    async (payload: PipelineTaskRequest) => {
      if (!dagId) return;
      setSaving(true);
      try {
        const created = await PipelineAPI.createTask(dagId, payload);
        setTasks((prev) => [...prev, created]);
        setSelectedTaskKey(created.taskKey);
        message.success(`任务已创建: ${created.taskKey}`);
        return created;
      } catch (err) {
        message.error(`创建失败: ${(err as Error).message}`);
        throw err;
      } finally {
        setSaving(false);
      }
    },
    [dagId, message],
  );

  const updateTask = useCallback(
    async (taskKey: string, payload: Partial<PipelineTaskRequest> & { taskType: PipelineTaskType }) => {
      if (!dagId) return;
      setSaving(true);
      try {
        const updated = await PipelineAPI.updateTask(dagId, taskKey, payload);
        setTasks((prev) => prev.map((t) => (t.taskKey === taskKey ? updated : t)));
        return updated;
      } catch (err) {
        message.error(`保存失败: ${(err as Error).message}`);
        throw err;
      } finally {
        setSaving(false);
      }
    },
    [dagId, message],
  );

  const deleteTask = useCallback(
    async (taskKey: string) => {
      if (!dagId) return;
      try {
        await PipelineAPI.deleteTask(dagId, taskKey);
        setTasks((prev) => prev.filter((t) => t.taskKey !== taskKey));
        setEdges((prev) =>
          prev.filter((e) => e.sourceKey !== taskKey && e.targetKey !== taskKey),
        );
        if (selectedTaskKey === taskKey) setSelectedTaskKey(undefined);
        message.success(`已删除任务: ${taskKey}`);
      } catch (err) {
        message.error(`删除失败: ${(err as Error).message}`);
      }
    },
    [dagId, message, selectedTaskKey],
  );

  const createEdge = useCallback(
    async (payload: PipelineTaskEdgeRequest) => {
      if (!dagId) return;
      try {
        const edge = await PipelineAPI.createEdge(dagId, payload);
        setEdges((prev) => [...prev, edge]);
        message.success(`已创建数据流边: ${edge.sourceKey} → ${edge.targetKey}`);
      } catch (err) {
        message.error(`连线失败: ${(err as Error).message}`);
      }
    },
    [dagId, message],
  );

  const deleteEdge = useCallback(
    async (sourceKey: string, targetKey: string) => {
      if (!dagId) return;
      try {
        await PipelineAPI.deleteEdge(dagId, sourceKey, targetKey);
        setEdges((prev) =>
          prev.filter((e) => !(e.sourceKey === sourceKey && e.targetKey === targetKey)),
        );
      } catch (err) {
        message.error(`删除边失败: ${(err as Error).message}`);
      }
    },
    [dagId, message],
  );

  const moveTask = useCallback(
    async (taskKey: string, position: { x: number; y: number }) => {
      if (!dagId) return;
      const task = tasks.find((item) => item.taskKey === taskKey);
      if (!task) return;
      const rounded = {
        x: Math.round(position.x),
        y: Math.round(position.y),
      };
      setTasks((prev) => prev.map((item) => (
        item.taskKey === taskKey
          ? { ...item, positionX: rounded.x, positionY: rounded.y }
          : item
      )));
      try {
        const updated = await PipelineAPI.updateTask(dagId, taskKey, {
          taskType: task.taskType,
          positionX: rounded.x,
          positionY: rounded.y,
        });
        setTasks((prev) => prev.map((item) => (item.taskKey === taskKey ? updated : item)));
      } catch (err) {
        message.error(`保存节点位置失败: ${(err as Error).message}`);
      }
    },
    [dagId, message, tasks],
  );

  const validate = useCallback(async () => {
    if (!dagId) return;
    try {
      const result = await PipelineAPI.validate(dagId);
      setValidation(result);
      // refresh tasks to pick up compile_status updates
      const fresh = await PipelineAPI.listTasks(dagId);
      setTasks(fresh);
      if (result.valid) message.success('校验通过');
      else message.warning('校验未通过，请检查节点错误');
      return result;
    } catch (err) {
      message.error(`校验失败: ${(err as Error).message}`);
    }
  }, [dagId, message]);

  const trigger = useCallback(async () => {
    if (!dagId) return;
    try {
      const { runId } = await PipelineAPI.trigger(dagId);
      message.success(`已触发流水线运行，runId=${runId.slice(0, 8)}…`);
      // P6-A: start live polling for this run
      setActiveRunId(runId);
    } catch (err) {
      message.error(`触发失败: ${(err as Error).message}`);
    }
  }, [dagId, message]);

  // P6-A: poll active run's status + task_runs every 5s; stop on terminal or unmount
  useEffect(() => {
    if (!dagId || !activeRunId) return;

    let cancelled = false;

    const pollOnce = async () => {
      try {
        // Read the run first because the backend refresh path also synchronizes
        // terminal task_run rows. Reading task runs in parallel can race and
        // leave cards stuck at QUEUED even after the run is already SUCCEEDED.
        const runPage = await OrchestrationAPI.listDagRuns(dagId, 0, 1);
        const runs = await PipelineAPI.listTaskRuns(dagId, activeRunId!);
        if (cancelled) return;
        const run = runPage.content?.find((r) => r.id === activeRunId);
        if (run) setLatestRun(run);
        setTaskRuns(runs);

        const terminal = run && (
          run.status === 'SUCCEEDED' || run.status === 'FAILED' || run.status === 'CANCELLED'
        );
        if (terminal) {
          setActiveRunId(undefined);
          if (pollTimer.current) {
            clearTimeout(pollTimer.current);
            pollTimer.current = null;
          }
          message.success(
            run.status === 'SUCCEEDED' ? '运行成功' : `运行${run.status}`,
          );
          return;
        }
        pollTimer.current = setTimeout(pollOnce, 5000);
      } catch (err) {
        if (cancelled) return;
        // Network errors shouldn't break the loop; retry after 5s
        pollTimer.current = setTimeout(pollOnce, 5000);
      }
    };

    pollOnce();
    return () => {
      cancelled = true;
      if (pollTimer.current) {
        clearTimeout(pollTimer.current);
        pollTimer.current = null;
      }
    };
  }, [dagId, activeRunId, message]);

  // task_runs keyed by taskKey for UI consumption
  const taskRunByKey = useMemo(() => {
    const m = new Map<string, TaskRun>();
    for (const tr of taskRuns) {
      m.set(tr.taskKey, tr);
    }
    return m;
  }, [taskRuns]);

  return {
    loading,
    pipeline,
    tasks,
    edges,
    selectedTaskKey,
    selectedTask,
    validation,
    saving,
    setSelectedTaskKey,
    reload: loadAll,
    createTask,
    updateTask,
    deleteTask,
    createEdge,
    deleteEdge,
    moveTask,
    validate,
    trigger,
    // P6-A: live run state
    latestRun,
    taskRuns,
    taskRunByKey,
    activeRunId,
  };
}

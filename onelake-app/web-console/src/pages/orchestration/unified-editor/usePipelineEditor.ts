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
import { PipelineAPI, PipelineVersionAPI, OrchestrationAPI, SecurityAPI } from '../../../api';
import { BizError } from '../../../api/http';
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
  ApprovalRequest,
} from '../../../types';

type PublishApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'STALE' | undefined;

const APPROVAL_APPLY_GRACE_MS = 30_000;

function publishApprovalView(
  enabled: boolean,
  pipeline: Pipeline,
  latest: ApprovalRequest | null,
): { status: PublishApprovalStatus; comment?: string } {
  if (!enabled) return { status: undefined };
  const cleanPublished = pipeline.status === 'PUBLISHED' && !pipeline.hasUnpublishedChanges;
  let status: PublishApprovalStatus;
  if (cleanPublished) {
    status = latest?.status === 'APPROVED' ? 'APPROVED' : undefined;
  } else if (latest?.status === 'PENDING' || latest?.status === 'REJECTED') {
    status = latest.status;
  } else if (latest?.status === 'APPROVED') {
    const decidedAt = Date.parse(latest.decidedAt || latest.createdAt);
    status = Number.isFinite(decidedAt) && Date.now() - decidedAt < APPROVAL_APPLY_GRACE_MS
      ? 'PENDING'
      : 'STALE';
  }
  return {
    status,
    comment: status === 'REJECTED'
      ? (latest?.comment || '审批人未填写拒绝原因')
      : status === 'STALE'
        ? '审批已经通过，但送审快照未生成生产版本；草稿可能已在送审后发生变化，请重新提交审批。'
      : latest?.comment,
  };
}

export interface PipelineEditorState {
  loading: boolean;
  loadError?: { message: string; noPermission: boolean };
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
  const [loadError, setLoadError] = useState<{ message: string; noPermission: boolean }>();
  const [pipeline, setPipeline] = useState<Pipeline | undefined>(undefined);
  const [tasks, setTasks] = useState<PipelineTask[]>([]);
  const [edges, setEdges] = useState<PipelineTaskEdge[]>([]);
  const [selectedTaskKey, setSelectedTaskKey] = useState<string | undefined>(undefined);
  const [validation, setValidation] = useState<PipelineValidationResult | undefined>(undefined);
  const [saving, setSaving] = useState(false);
  const [currentPublishedVersion, setCurrentPublishedVersion] = useState<number | undefined>(undefined);
  const [publishApprovalEnabled, setPublishApprovalEnabled] = useState(false);
  const [publishApprovalStatus, setPublishApprovalStatus] = useState<PublishApprovalStatus>();
  const [publishApprovalComment, setPublishApprovalComment] = useState<string | undefined>(undefined);

  // P6-A: live run state (auto-poll)
  const [latestRun, setLatestRun] = useState<JobRun | undefined>(undefined);
  const [taskRuns, setTaskRuns] = useState<TaskRun[]>([]);
  const [activeRunId, setActiveRunId] = useState<string | undefined>(undefined);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const approvalPollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const markPublishedDraftChanged = useCallback(() => {
    setPipeline((current) => current?.status === 'PUBLISHED'
      ? { ...current, hasUnpublishedChanges: true }
      : current);
    if (publishApprovalStatus === 'PENDING') {
      setPublishApprovalStatus('STALE');
      setPublishApprovalComment('草稿已在送审后修改，请重新提交审批。');
    } else if (publishApprovalStatus === 'APPROVED' || publishApprovalStatus === 'REJECTED') {
      setPublishApprovalStatus(undefined);
      setPublishApprovalComment(undefined);
    }
  }, [publishApprovalStatus]);

  const loadAll = useCallback(async () => {
    if (!dagId) {
      setLoading(false);
      setLoadError(undefined);
      setPipeline(undefined);
      setTasks([]);
      setEdges([]);
      setSelectedTaskKey(undefined);
      setValidation(undefined);
      setCurrentPublishedVersion(undefined);
      setPublishApprovalEnabled(false);
      setPublishApprovalStatus(undefined);
      setPublishApprovalComment(undefined);
      return;
    }
    setLoading(true);
    setLoadError(undefined);
    try {
      const [p, ts, es, publishApprovalConfig, publishApprovalState] = await Promise.all([
        PipelineAPI.get(dagId),
        PipelineAPI.listTasks(dagId),
        PipelineAPI.listEdges(dagId),
        PipelineAPI.publishApprovalConfig()
          // 配置查询失败时按开启处理，避免网络故障意外绕过发布门控。
          .catch(() => ({ enabled: true })),
        SecurityAPI.publishApprovalState(dagId)
          .then((latest) => {
            return latest;
          })
          .catch(() => null),
      ]);
      setPipeline(p);
      setTasks(ts);
      setEdges(es);
      setPublishApprovalEnabled(publishApprovalConfig.enabled);
      const approval = publishApprovalView(publishApprovalConfig.enabled, p, publishApprovalState);
      setPublishApprovalStatus(approval.status);
      setPublishApprovalComment(approval.comment);
    } catch (err) {
      setPipeline(undefined);
      setTasks([]);
      setEdges([]);
      setLoadError(toEditorLoadError(err));
      message.error(`加载流水线失败: ${(err as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [dagId, message]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // 版本号独立于编辑器主数据加载；瞬时失败时持续重试，避免把网络错误误判为“无版本”。
  useEffect(() => {
    const publishedVersionId = pipeline?.publishedVersionId;
    if (!dagId || !publishedVersionId) {
      setCurrentPublishedVersion(undefined);
      return;
    }

    setCurrentPublishedVersion(undefined);
    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    const loadCurrentVersion = async () => {
      try {
        const versions = await PipelineVersionAPI.list(dagId);
        if (cancelled) return;
        const version = versions.find((item) => item.id === publishedVersionId)?.version;
        if (version !== undefined) {
          setCurrentPublishedVersion(version);
          return;
        }
      } catch {
        // 保留已有显示；版本服务恢复后由下一轮重试补齐。
      }
      if (!cancelled) retryTimer = setTimeout(loadCurrentVersion, 3000);
    };

    void loadCurrentVersion();
    return () => {
      cancelled = true;
      if (retryTimer) clearTimeout(retryTimer);
    };
  }, [dagId, pipeline?.publishedVersionId]);

  // 发布送审后只轮询状态摘要，不触发编辑器全量 loading；通过或拒绝后自动停止。
  useEffect(() => {
    if (!dagId || !publishApprovalEnabled || publishApprovalStatus !== 'PENDING') return;

    let cancelled = false;
    const pollApproval = async () => {
      try {
        const [freshPipeline, latestApproval] = await Promise.all([
          PipelineAPI.get(dagId),
          SecurityAPI.publishApprovalState(dagId).catch(() => null),
        ]);
        if (cancelled) return;

        setPipeline(freshPipeline);
        const approval = publishApprovalView(true, freshPipeline, latestApproval);

        if (approval.status === 'APPROVED') {
          setPublishApprovalStatus('APPROVED');
          setPublishApprovalComment(approval.comment);
          message.success('发布审批已通过，生产版本已更新');
          return;
        }
        if (approval.status === 'REJECTED') {
          setPublishApprovalStatus('REJECTED');
          setPublishApprovalComment(approval.comment);
          message.error(`发布审批已拒绝：${approval.comment}`);
          return;
        }
        if (approval.status === 'STALE') {
          setPublishApprovalStatus('STALE');
          setPublishApprovalComment(approval.comment);
          message.warning(approval.comment || '送审草稿已变化，请重新提交审批');
          return;
        }
        if (latestApproval?.status === 'CANCELED') {
          setPublishApprovalStatus(undefined);
          setPublishApprovalComment(latestApproval.comment);
          message.info('发布审批已取消，可修改草稿后重新提交');
          return;
        }
      } catch {
        // 临时网络错误保留等待态，下一个周期继续查询。
      }
      if (!cancelled) approvalPollTimer.current = setTimeout(pollApproval, 3000);
    };

    approvalPollTimer.current = setTimeout(pollApproval, 3000);
    return () => {
      cancelled = true;
      if (approvalPollTimer.current) {
        clearTimeout(approvalPollTimer.current);
        approvalPollTimer.current = null;
      }
    };
  }, [dagId, message, publishApprovalEnabled, publishApprovalStatus]);

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
        markPublishedDraftChanged();
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
    [dagId, markPublishedDraftChanged, message],
  );

  const updateTask = useCallback(
    async (taskKey: string, payload: Partial<PipelineTaskRequest> & { taskType: PipelineTaskType }) => {
      if (!dagId) return;
      setSaving(true);
      try {
        const updated = await PipelineAPI.updateTask(dagId, taskKey, payload);
        setTasks((prev) => prev.map((t) => (t.taskKey === taskKey ? updated : t)));
        markPublishedDraftChanged();
        return updated;
      } catch (err) {
        message.error(`保存失败: ${(err as Error).message}`);
        throw err;
      } finally {
        setSaving(false);
      }
    },
    [dagId, markPublishedDraftChanged, message],
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
        markPublishedDraftChanged();
        message.success(`已删除任务: ${taskKey}`);
      } catch (err) {
        message.error(`删除失败: ${(err as Error).message}`);
      }
    },
    [dagId, markPublishedDraftChanged, message, selectedTaskKey],
  );

  const createEdge = useCallback(
    async (payload: PipelineTaskEdgeRequest) => {
      if (!dagId) return;
      try {
        const edge = await PipelineAPI.createEdge(dagId, payload);
        setEdges((prev) => [...prev, edge]);
        markPublishedDraftChanged();
        message.success(`已创建数据流边: ${edge.sourceKey} → ${edge.targetKey}`);
      } catch (err) {
        message.error(`连线失败: ${(err as Error).message}`);
      }
    },
    [dagId, markPublishedDraftChanged, message],
  );

  const deleteEdge = useCallback(
    async (sourceKey: string, targetKey: string) => {
      if (!dagId) return;
      try {
        await PipelineAPI.deleteEdge(dagId, sourceKey, targetKey);
        setEdges((prev) =>
          prev.filter((e) => !(e.sourceKey === sourceKey && e.targetKey === targetKey)),
        );
        markPublishedDraftChanged();
      } catch (err) {
        message.error(`删除边失败: ${(err as Error).message}`);
      }
    },
    [dagId, markPublishedDraftChanged, message],
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
        markPublishedDraftChanged();
      } catch (err) {
        message.error(`保存节点位置失败: ${(err as Error).message}`);
      }
    },
    [dagId, markPublishedDraftChanged, message, tasks],
  );

  const validate = useCallback(async () => {
    if (!dagId) return;
    const result = await PipelineAPI.validate(dagId);
    setValidation(result);
    // refresh tasks to pick up compile_status updates
    const fresh = await PipelineAPI.listTasks(dagId);
    setTasks(fresh);
    return result;
  }, [dagId]);

  const trigger = useCallback(async () => {
    if (!dagId) return;
    try {
      const env = pipeline?.status === 'PUBLISHED' && !pipeline.hasUnpublishedChanges ? 'PROD' : 'DEV';
      const { runId } = await PipelineAPI.trigger(dagId, 'MANUAL', env);
      message.success(`已触发${env === 'DEV' ? '试跑' : '生产运行'}，runId=${runId.slice(0, 8)}…`);
      // P6-A: start live polling for this run
      setActiveRunId(runId);
    } catch (err) {
      message.error(`触发失败: ${(err as Error).message}`);
    }
  }, [dagId, message, pipeline]);

  const publish = useCallback(async () => {
    if (!dagId || !pipeline) return;
    let next = pipeline;
    if (next.status === 'DRAFT' || !next.status) {
      next = await PipelineAPI.updateStatus(dagId, 'VALIDATED');
    }
    if (next.status === 'VALIDATED' || next.status === 'PUBLISHED') {
      next = await PipelineAPI.updateStatus(dagId, 'PUBLISHED');
    }
    setPipeline(next);
    const waitingForApproval = publishApprovalEnabled
      && (next.status !== 'PUBLISHED' || Boolean(next.hasUnpublishedChanges));
    setPublishApprovalStatus(waitingForApproval ? 'PENDING' : undefined);
    setPublishApprovalComment(undefined);
    const freshTasks = await PipelineAPI.listTasks(dagId);
    setTasks(freshTasks);
    return next;
  }, [dagId, pipeline, publishApprovalEnabled]);

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
    loadError,
    pipeline,
    tasks,
    edges,
    selectedTaskKey,
    selectedTask,
    validation,
    saving,
    currentPublishedVersion,
    publishApprovalEnabled,
    publishApprovalStatus,
    publishApprovalComment,
    setSelectedTaskKey,
    reload: loadAll,
    createTask,
    updateTask,
    deleteTask,
    createEdge,
    deleteEdge,
    moveTask,
    validate,
    publish,
    trigger,
    // P6-A: live run state
    latestRun,
    taskRuns,
    taskRunByKey,
    activeRunId,
  };
}

function toEditorLoadError(error: unknown) {
  const code = error instanceof BizError ? error.code : undefined;
  const status = (error as { response?: { status?: number } })?.response?.status;
  const noPermission = status === 401 || status === 403 || code === 40100 || code === 40300;
  if (status === 401 || code === 40100) return { noPermission, message: '登录状态已失效，请重新登录。' };
  if (status === 403 || code === 40300) return { noPermission, message: '当前账号没有查看或编辑该流水线的权限。' };
  if (status === 404) return { noPermission, message: '未找到该流水线，可能已被删除。' };
  return {
    noPermission,
    message: error instanceof Error && error.message ? error.message : '流水线服务暂时不可用，请稍后重试。',
  };
}

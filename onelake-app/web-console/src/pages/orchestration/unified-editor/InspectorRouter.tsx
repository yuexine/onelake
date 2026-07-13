/**
 * Inspector router: dispatches to the right inspector by task_type.
 *
 * <p>The shell owns the common two-column details layout, while each inspector only
 * renders the editable configuration for its task type.
 */
import { StateView } from '../../../components';
import type { PipelineTask, PipelineTaskEdge, PipelineTaskRequest, PipelineTaskType } from '../../../types';
import { InspectorLayout } from './InspectorLayout';
import { QualityGateInspector } from './inspectors/QualityGateInspector';
import { SparkInspector } from './inspectors/SparkInspector';
import { SyncRefInspector } from './inspectors/SyncRefInspector';
import { ControlFlowInspector } from './inspectors/ControlFlowInspector';
import { NotifyAssertionInspector } from './inspectors/NotifyAssertionInspector';
import { ObserveInspector } from './inspectors/ObserveInspector';
import { ScriptInspector } from './inspectors/ScriptInspector';
import { SubPipelineInspector } from './inspectors/SubPipelineInspector';
import { TrinoSqlInspector } from './inspectors/TrinoSqlInspector';
import { NodeParamEditor } from './NodeParamEditor';
import type { InspectorValidationErrors } from './inspectorValidation';

export interface InspectorProps {
  dagId: string;
  task: PipelineTask;
  tasks: PipelineTask[];
  edges: PipelineTaskEdge[];
  onChange: (patch: Partial<PipelineTaskRequest> & { taskType: PipelineTaskType }) => void;
  onSave: () => void;
  saving: boolean;
  validationErrors: InspectorValidationErrors;
  onValidationChange?: (taskKey: string, field: string, error?: string) => void;
}

export function InspectorRouter(props: InspectorProps | { task?: undefined }) {
  if (!('task' in props) || !props.task) {
    return (
      <StateView
        state="empty"
        title="尚未选择任务"
        description="选择一个任务节点查看并编辑配置。"
      />
    );
  }
  const { task } = props;
  const inspector = (() => {
    switch (task.taskType) {
    case 'QUALITY_GATE':
      return <QualityGateInspector {...props} />;
    case 'SYNC_REF':
      return <SyncRefInspector {...props} />;
    case 'SPARK_SQL':
    case 'PYSPARK':
      return <SparkInspector {...props} />;
    case 'TRINO_SQL':
      return <TrinoSqlInspector {...props} />;
    case 'PYTHON':
    case 'SHELL':
      return <ScriptInspector {...props} />;
    case 'BRANCH':
    case 'CONDITION':
      return <ControlFlowInspector key={task.taskKey} {...props} />;
    case 'SENSOR':
    case 'WAIT':
      return <ObserveInspector {...props} />;
    case 'SUB_PIPELINE':
      return <SubPipelineInspector {...props} />;
    case 'NOTIFY':
    case 'ASSERTION':
      return <NotifyAssertionInspector {...props} />;
    default:
      return <StateView state="error" title="未知任务类型" description={String(task.taskType)} />;
    }
  })();

  return (
    <InspectorLayout task={task} tasks={props.tasks} edges={props.edges}>
      {inspector}
      <NodeParamEditor dagId={props.dagId} taskKey={task.taskKey} />
    </InspectorLayout>
  );
}

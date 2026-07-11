/**
 * Inspector router: dispatches to the right inspector by task_type.
 *
 * <p>The shell owns the common two-column details layout, while each inspector only
 * renders the editable configuration for its task type.
 */
import { Empty } from 'antd';
import type { PipelineTask, PipelineTaskEdge, PipelineTaskRequest, PipelineTaskType } from '../../../types';
import { InspectorLayout } from './InspectorLayout';
import { QualityGateInspector } from './inspectors/QualityGateInspector';
import { SparkInspector } from './inspectors/SparkInspector';
import { SyncRefInspector } from './inspectors/SyncRefInspector';
import { NodeParamEditor } from './NodeParamEditor';

export interface InspectorProps {
  dagId: string;
  task: PipelineTask;
  tasks: PipelineTask[];
  edges: PipelineTaskEdge[];
  onChange: (patch: Partial<PipelineTaskRequest> & { taskType: PipelineTaskType }) => void;
  onSave: () => void;
  saving: boolean;
}

export function InspectorRouter(props: InspectorProps | { task?: undefined }) {
  if (!('task' in props) || !props.task) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description="选择一个任务节点查看详情"
        style={{ marginTop: 80 }}
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
    default:
      return <Empty description={`未知任务类型: ${task.taskType}`} />;
    }
  })();

  return (
    <InspectorLayout task={task} tasks={props.tasks} edges={props.edges}>
      {inspector}
      <NodeParamEditor dagId={props.dagId} taskKey={task.taskKey} />
    </InspectorLayout>
  );
}

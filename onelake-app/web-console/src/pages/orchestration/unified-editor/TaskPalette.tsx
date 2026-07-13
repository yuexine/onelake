/**
 * Left panel: task type palette grouped by category.
 *
 * <p>Clicking a type opens a "create task" dialog, and dragging a type onto the
 * DAG canvas opens the same dialog at the drop position.
 */
import { Collapse, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { TASK_TYPE_GROUPS, type TaskTypeMeta } from './taskTypes';
import type { PipelineTaskType } from '../../../types';

const { Text } = Typography;

interface Props {
  onAdd: (type: PipelineTaskType, meta: TaskTypeMeta) => void;
  disabled?: boolean;
}

export function TaskPalette({ onAdd, disabled }: Props) {
  const items = TASK_TYPE_GROUPS.map((g) => ({
    key: g.group,
    label: (
      <span>
        <Text strong>{g.label}</Text>
        <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
          {g.items.length}
        </Text>
      </span>
    ),
    children: (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {g.items.map((item) => (
          <button
            key={`${item.type}:${item.preset ?? 'base'}:${item.name}`}
            type="button"
            draggable={!disabled}
            disabled={disabled}
            onPointerDown={(event) => {
              if (disabled) return;
              window.dispatchEvent(new CustomEvent('onelake:pipeline-palette-drag-start', {
                detail: {
                  meta: item,
                  clientX: event.clientX,
                  clientY: event.clientY,
                },
              }));
            }}
            onDragStart={(event) => {
              event.dataTransfer.effectAllowed = 'copy';
              event.dataTransfer.setData('application/x-onelake-task', JSON.stringify(item));
            }}
            onClick={() => onAdd(item.type, item)}
            style={{
              textAlign: 'left',
              padding: '8px 10px',
              borderRadius: 6,
              border: '1px solid var(--ol-border, #e4e7eb)',
              background: 'var(--ol-fill-soft, #fafafa)',
              cursor: disabled ? 'not-allowed' : 'pointer',
              opacity: disabled ? 0.5 : 1,
              transition: 'all 0.15s',
            }}
            title={item.description}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 16 }}>{item.icon}</span>
              <Text strong style={{ fontSize: 13 }}>
                {item.name}
              </Text>
              {item.contractOnly && (
                <Text type="secondary" style={{ fontSize: 10, marginLeft: 'auto' }}>
                  契约态
                </Text>
              )}
            </div>
            <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 4 }}>
              {item.description}
            </Text>
          </button>
        ))}
      </div>
    ),
  }));

  return (
    <div
      data-testid="pipeline-task-palette"
      style={{ height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
    >
      <div
        style={{
          padding: '12px 16px 10px',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          borderBottom: '1px solid var(--ol-border, #e4e7eb)',
          background: 'var(--ol-bg, #fff)',
          flex: '0 0 auto',
        }}
      >
        <PlusOutlined />
        <Text strong>添加任务</Text>
      </div>
      <div data-testid="pipeline-task-palette-scroll" style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 8 }}>
        <Collapse items={items} defaultActiveKey={TASK_TYPE_GROUPS.map((g) => g.group)} ghost size="small" />
      </div>
    </div>
  );
}

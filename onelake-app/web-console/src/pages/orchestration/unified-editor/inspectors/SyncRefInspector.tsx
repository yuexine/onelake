/**
 * SYNC_REF inspector — pick the ODS source FQN that this task depends on.
 *
 * <p>When the referenced table is loaded (integration.table.loaded event),
 * PipelineSyncRefTriggerHandler matches target_fqn and triggers the pipeline.
 */
import { useEffect, useMemo, useState } from 'react';
import { Form, Input, Select, Tag, Typography } from 'antd';
import { CatalogAPI, IntegrationAPI } from '../../../../api';
import type { Asset, SyncTask } from '../../../../types';
import type { InspectorProps } from '../InspectorRouter';

const { Text } = Typography;

export function SyncRefInspector({ task, onChange }: InspectorProps) {
  const [assets, setAssets] = useState<Asset[]>([]);
  const [syncTasks, setSyncTasks] = useState<SyncTask[]>([]);
  const [loadingAssets, setLoadingAssets] = useState(false);
  const [loadingTasks, setLoadingTasks] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoadingAssets(true);
    CatalogAPI.listAssets({ layer: 'ODS' })
      .then((items) => {
        if (alive) setAssets(items);
      })
      .catch(() => {
        if (alive) setAssets([]);
      })
      .finally(() => {
        if (alive) setLoadingAssets(false);
      });

    setLoadingTasks(true);
    IntegrationAPI.listSyncTasks()
      .then((items) => {
        if (alive) setSyncTasks(items);
      })
      .catch(() => {
        if (alive) setSyncTasks([]);
      })
      .finally(() => {
        if (alive) setLoadingTasks(false);
      });

    return () => {
      alive = false;
    };
  }, []);

  const assetOptions = useMemo(() => {
    const current = task.targetFqn && !assets.some((asset) => asset.fqn === task.targetFqn)
      ? [{
          value: task.targetFqn,
          label: <OptionLabel title={task.targetFqn} meta="当前配置" />,
          searchText: task.targetFqn,
        }]
      : [];
    return [
      ...current,
      ...assets.map((asset) => {
        const meta = [asset.name, asset.domain ? `${asset.domain}域` : undefined, asset.rows ? `${asset.rows} 行` : undefined]
          .filter(Boolean)
          .join(' · ');
        return {
          value: asset.fqn,
          label: <OptionLabel title={asset.fqn} meta={meta} />,
          searchText: `${asset.fqn} ${asset.name} ${asset.domain ?? ''}`,
        };
      }),
    ];
  }, [assets, task.targetFqn]);

  const syncTaskOptions = useMemo(() => syncTasks.map((item) => ({
    value: item.id,
    title: item.name,
    label: (
      <OptionLabel
        title={item.name}
        meta={`${item.targetTable} · ${item.mode} · ${item.status}`}
      />
    ),
    searchText: `${item.id} ${item.name} ${item.sourceTable} ${item.targetTable} ${item.sourceName}`,
    task: item,
  })), [syncTasks]);

  const selectedSyncTask = syncTasks.find((item) => item.id === task.syncTaskId);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div
        style={{
          border: '1px solid var(--ol-border, #dfe7f1)',
          borderRadius: 10,
          padding: '8px 10px',
          background: 'var(--ol-bg-elevated, #fbfdff)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
        }}
      >
        <div style={{ minWidth: 0 }}>
          <Text strong style={{ fontSize: 13, whiteSpace: 'nowrap' }}>同步引用</Text>
          <Text type="secondary" style={{ marginLeft: 8, fontSize: 12, lineHeight: '20px' }}>
            选择 ODS 表作为数据流起点；目标表 FQN 一致时触发下游。
          </Text>
        </div>
        <Tag color="blue" style={{ margin: 0, whiteSpace: 'nowrap' }}>
          FQN 匹配
        </Tag>
      </div>
      <Form layout="vertical" size="small">
        <Form.Item
          label="上游 ODS 表"
          required
          tooltip="从资产目录检索并选择已采集的 ODS 表。"
          style={{ marginBottom: 12 }}
        >
          <Select
            showSearch
            optionLabelProp="value"
            options={assetOptions}
            value={task.targetFqn}
            placeholder="搜索表名、FQN 或业务域"
            allowClear
            loading={loadingAssets}
            filterOption={(input, option) =>
              `${option?.value ?? ''} ${(option as { searchText?: string })?.searchText ?? ''}`
                .toLowerCase()
                .includes(input.toLowerCase())
            }
            notFoundContent={loadingAssets ? '加载 ODS 表...' : '未找到匹配 ODS 表'}
            onChange={(value) => {
              onChange({
                taskType: 'SYNC_REF',
                targetFqn: value || undefined,
                syncTaskId: selectedSyncTask && selectedSyncTask.targetTable !== value ? undefined : task.syncTaskId,
              });
            }}
          />
          <Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
            选择后会作为该节点输出资产和事件匹配键；加载事件中的目标表 FQN 一致时触发下游。
          </Text>
        </Form.Item>
        <Form.Item
          label="关联采集任务（可选）"
          tooltip="选择后仅用于回填表 FQN 和展示关联；事件触发仍以表 FQN 为准。"
          style={{ marginBottom: 0 }}
        >
          <Select
            showSearch
            allowClear
            loading={loadingTasks}
            optionLabelProp="title"
            value={task.syncTaskId}
            placeholder="可搜索采集任务名称、目标表或任务 ID"
            options={syncTaskOptions}
            filterOption={(input, option) => {
              const text = `${option?.value ?? ''} ${(option as { searchText?: string })?.searchText ?? ''}`;
              return text.toLowerCase().includes(input.toLowerCase());
            }}
            onChange={(value) => {
              const selected = syncTasks.find((item) => item.id === value);
              onChange({
                taskType: 'SYNC_REF',
                syncTaskId: value || undefined,
                targetFqn: selected?.targetTable ?? task.targetFqn,
              });
            }}
          />
          <Text type="secondary" style={{ display: 'block', marginTop: 6, fontSize: 12 }}>
            不选择采集任务时，仍会按上游表 FQN 匹配 `integration.table.loaded` 事件；只是不会展示具体采集任务绑定。
          </Text>
        </Form.Item>
      </Form>
    </div>
  );
}

function OptionLabel({ title, meta }: { title: string; meta?: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <span>{title}</span>
      {meta && <span style={{ color: '#64748b', fontSize: 12 }}>{meta}</span>}
    </div>
  );
}

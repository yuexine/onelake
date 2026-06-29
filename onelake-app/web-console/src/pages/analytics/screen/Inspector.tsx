/**
 * 检查器 Inspector（属性 / 数据 / 交互 三 Tab）。
 *
 * - 属性：标题 / 样式 / ECharts option 高级编辑
 * - 数据：数据集选择 + 字段映射（dim / measures / filters）+ 刷新间隔
 * - 交互：事件（P3 启用）
 */
import { useMemo } from 'react';
import {
  Tabs, Form, Input, InputNumber, Select, Button, Space, Empty, Divider, Row, Col, message,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { ScreenSpec, WidgetNode, DataBinding } from './types';
import type { AnalyticsDataset } from '../../../api';
import { AnalyticsAPI } from '../../../api';

const { TextArea } = Input;
const { Option } = Select;

export interface InspectorProps {
  node: WidgetNode | null;
  spec: ScreenSpec;
  datasets: AnalyticsDataset[];
  onChange: (node: WidgetNode) => void;
  onRemove: () => void;
}

export function Inspector({ node, spec, datasets, onChange, onRemove }: InspectorProps) {
  const fields = useMemo(() => {
    const ds = datasets.find((d) => d.id === node?.data?.datasetId);
    return ds?.fieldSchema ?? [];
  }, [node?.data?.datasetId, datasets]);

  if (!node) {
    return <Empty style={{ marginTop: 80 }} description="选中组件后配置" />;
  }

  const patch = (p: Partial<WidgetNode>) => onChange({ ...node, ...p });

  // ===== 属性 Tab =====
  const properties = (
    <Form layout="vertical" size="small">
      <Form.Item label="标题">
        <Input value={node.title ?? ''} onChange={(e) => patch({ title: e.target.value })} />
      </Form.Item>
      <Form.Item label="层级 z-index">
        <InputNumber
          value={node.layout.z}
          onChange={(v) => patch({ layout: { ...node.layout, z: Number(v ?? 1) } })}
          min={0} max={10} style={{ width: '100%' }}
        />
      </Form.Item>
      <Form.Item label="尺寸（宽×高 单元格）">
        <Row gutter={8}>
          <Col span={12}>
            <InputNumber
              value={node.layout.w}
              onChange={(v) => patch({ layout: { ...node.layout, w: Number(v ?? 1) } })}
              min={1} max={48} style={{ width: '100%' }}
            />
          </Col>
          <Col span={12}>
            <InputNumber
              value={node.layout.h}
              onChange={(v) => patch({ layout: { ...node.layout, h: Number(v ?? 1) } })}
              min={1} max={40} style={{ width: '100%' }}
            />
          </Col>
        </Row>
      </Form.Item>
      {node.type === 'superset' && (
        <Form.Item label="Superset Dashboard UUID" required>
          <Input
            value={node.supersetUuid ?? ''}
            onChange={(e) => patch({ supersetUuid: e.target.value })}
            placeholder="abc-def-123（从 Superset URL 取）"
          />
        </Form.Item>
      )}
      <Form.Item label="ECharts option 覆盖（高级）" tooltip="JSON 格式，会与 buildOption 浅合并">
        <TextArea
          rows={6}
          value={node.option ? JSON.stringify(node.option, null, 2) : ''}
          onChange={(e) => {
            try {
              const v = e.target.value.trim();
              patch({ option: v ? JSON.parse(v) : undefined });
            } catch (err) {
              // 解析失败不更新（避免键入过程报错）
            }
          }}
          placeholder='{\n  "series": [{ "smooth": false }]\n}'
        />
      </Form.Item>
    </Form>
  );

  // ===== 数据 Tab =====
  const dataBinding: DataBinding = node.data ?? { dimensions: [], measures: [] };
  const setData = (d: Partial<DataBinding>) => patch({ data: { ...dataBinding, ...d } });

  const dataTab = (
    <Form layout="vertical" size="small">
      <Form.Item label="数据集">
        <Select
          value={node.data?.datasetId}
          onChange={(v) => setData({ datasetId: v })}
          placeholder="选择数据集"
          showSearch
          optionFilterProp="label"
          options={datasets.map((d) => ({ label: d.name, value: d.id }))}
        />
      </Form.Item>

      {!fields.length ? (
        <Empty description="数据集无字段 schema，请先在数据集详情中维护" />
      ) : (
        <>
          <Form.Item label="维度（X 轴 / 分类）">
            <Select
              mode="multiple"
              value={dataBinding.dimensions}
              onChange={(v) => setData({ dimensions: v })}
              options={fields.map((f) => ({ label: f.name, value: f.name }))}
            />
          </Form.Item>
          <Form.Item label="指标（Y 轴 / 数值）">
            {dataBinding.measures.map((m, i) => (
              <Row gutter={4} key={i} style={{ marginBottom: 4 }}>
                <Col span={12}>
                  <Select
                    value={m.field}
                    onChange={(v) => {
                      const arr = [...dataBinding.measures];
                      arr[i] = { ...arr[i], field: v };
                      setData({ measures: arr });
                    }}
                    options={fields.map((f) => ({ label: f.name, value: f.name }))}
                    size="small"
                  />
                </Col>
                <Col span={9}>
                  <Select
                    value={m.agg}
                    onChange={(v) => {
                      const arr = [...dataBinding.measures];
                      arr[i] = { ...arr[i], agg: v };
                      setData({ measures: arr });
                    }}
                    size="small"
                    options={[
                      { label: 'SUM', value: 'sum' },
                      { label: 'AVG', value: 'avg' },
                      { label: 'MAX', value: 'max' },
                      { label: 'MIN', value: 'min' },
                      { label: 'COUNT', value: 'count' },
                    ]}
                  />
                </Col>
                <Col span={3}>
                  <Button
                    size="small" type="text" danger icon={<DeleteOutlined />}
                    onClick={() => setData({ measures: dataBinding.measures.filter((_, j) => j !== i) })}
                  />
                </Col>
              </Row>
            ))}
            <Button
              size="small" type="dashed" icon={<PlusOutlined />} block
              onClick={() => setData({ measures: [...dataBinding.measures, { field: fields[0].name, agg: 'sum' }] })}
            >
              添加指标
            </Button>
          </Form.Item>

          <Divider style={{ margin: '12px 0' }} />

          <Form.Item label="刷新间隔（秒，最小 5）">
            <InputNumber
              value={dataBinding.refreshSec}
              onChange={(v) => setData({ refreshSec: v ? Math.max(5, Number(v)) : undefined })}
              min={5} max={600} style={{ width: '100%' }}
              placeholder="不刷新"
            />
          </Form.Item>
        </>
      )}
    </Form>
  );

  // ===== 交互 Tab（钻取联动 · P5-B） =====
  const events = node.events ?? [];
  const patchEvents = (newEvents: typeof events) => patch({ events: newEvents });
  const interactionTab = (
    <div>
      <div style={{ marginBottom: 8, color: '#888', fontSize: 12 }}>
        点击本组件 → 触发以下事件。可被全局筛选器条 / 其他组件订阅。
      </div>

      {/* 已配置事件列表 */}
      {events.length === 0 ? (
        <Empty style={{ margin: '12px 0' }} description="未配置事件" />
      ) : (
        events.map((ev, i) => (
          <div key={i} style={{
            padding: 8, marginBottom: 8, background: 'rgba(255,255,255,0.04)',
            border: '1px solid #243549', borderRadius: 4,
          }}>
            <Space style={{ marginBottom: 6, justifyContent: 'space-between', width: '100%' }}>
              <Select
                size="small"
                value={ev.type}
                onChange={(v) => {
                  const arr = [...events];
                  arr[i] = { ...arr[i], type: v };
                  patchEvents(arr);
                }}
                style={{ width: 100 }}
                options={[
                  { label: '过滤', value: 'filter' },
                  { label: '跳转', value: 'jump' },
                ]}
              />
              <Button size="small" type="text" danger icon={<DeleteOutlined />}
                      onClick={() => patchEvents(events.filter((_, j) => j !== i))} />
            </Space>

            {ev.type === 'filter' ? (
              <>
                <div style={{ marginBottom: 4, fontSize: 11, color: '#aaa' }}>写入变量 key：</div>
                <Input
                  size="small"
                  placeholder="如 region 或 product_category"
                  value={ev.targetVar ?? ''}
                  onChange={(e) => {
                    const arr = [...events];
                    arr[i] = { ...arr[i], targetVar: e.target.value };
                    patchEvents(arr);
                  }}
                />
                <div style={{ marginTop: 6, fontSize: 11, color: '#aaa' }}>
                  其他组件在 data.filters 中加一项 fromVar="{ev.targetVar || '<key>'}" 即可订阅。
                </div>
              </>
            ) : (
              <>
                <div style={{ marginBottom: 4, fontSize: 11, color: '#aaa' }}>跳转目标：</div>
                <Input
                  size="small"
                  placeholder="dashboardId 或 https://..."
                  value={ev.target ?? ''}
                  onChange={(e) => {
                    const arr = [...events];
                    arr[i] = { ...arr[i], target: e.target.value };
                    patchEvents(arr);
                  }}
                />
              </>
            )}
          </div>
        ))
      )}

      <Button
        size="small"
        type="dashed"
        icon={<PlusOutlined />}
        block
        onClick={() => patchEvents([...events, { type: 'filter', targetVar: '' } as any])}
      >
        添加事件
      </Button>

      <Divider style={{ margin: '12px 0' }} />
      <div style={{ fontSize: 11, color: '#666', lineHeight: 1.6 }}>
        <strong>用法示例：</strong><br />
        ① 在本组件添加事件：filter → 写入变量 region<br />
        ② 在另一个组件的 "数据" Tab 中，点 + 添加 filter，fromVar 选 region<br />
        ③ 用户点击本组件 X 轴 "华东" 时，另一个组件自动只查 region=华东 的数据
      </div>
    </div>
  );

  return (
    <div style={{ padding: 12 }}>
      <Space style={{ marginBottom: 12, justifyContent: 'space-between', width: '100%' }}>
        <strong>{node.type}</strong>
        <Button type="text" danger icon={<DeleteOutlined />} onClick={onRemove}>删除</Button>
      </Space>
      <Tabs
        size="small"
        defaultActiveKey="data"
        items={[
          { key: 'data', label: '数据', children: dataTab },
          { key: 'prop', label: '属性', children: properties },
          { key: 'inter', label: '交互', children: interactionTab },
        ]}
      />
    </div>
  );
}

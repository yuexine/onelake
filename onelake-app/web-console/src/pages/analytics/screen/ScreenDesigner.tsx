/**
 * 大屏设计器（核心编排页：palette + canvas + inspector）。
 *
 * 设计参考（§4.2）：三段式布局，与"统一编辑器"风格一致，降低用户学习成本。
 *
 * 状态：ScreenSpec = { canvas, widgets[], globalVars[] }
 * 持久化：AnalyticsAPI.saveDashboard(id, { spec, expectedVersion })
 *
 * P2 已实现：拖拽 + Inspector + 数据绑定 + 15 组件渲染。
 * P3 待加：发布 + 公开分享入口（位于顶部 PageHeader）。
 */
import { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Layout, Button, Space, Input, Select, message, Spin } from 'antd';
import {
  ArrowLeftOutlined, SaveOutlined, SendOutlined, EyeOutlined,
} from '@ant-design/icons';
import { AnalyticsAPI, type AnalyticsDashboard, type AnalyticsDataset } from '../../../api';
import { WIDGET_REGISTRY } from './registry';
import { ScreenCanvas } from './ScreenCanvas';
import { Inspector } from './Inspector';
import { Palette } from './Palette';
import { PublishDialog } from './PublishDialog';
import { GlobalFilterBar } from './GlobalFilterBar';
import { LinkageProvider } from './linkage';
import type { ScreenSpec, WidgetNode, WidgetType, GlobalVar } from './types';
import { DEFAULT_CANVAS } from './types';

const { Sider, Content, Header, Sider: RightSider } = Layout;

export default function ScreenDesigner({ readOnly = false }: { readOnly?: boolean }) {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [dashboard, setDashboard] = useState<AnalyticsDashboard | null>(null);
  const [spec, setSpec] = useState<ScreenSpec>({ canvas: DEFAULT_CANVAS, widgets: [] });
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [datasets, setDatasets] = useState<AnalyticsDataset[]>([]);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const [publishOpen, setPublishOpen] = useState(false);

  // 加载大屏 + 数据集列表
  useEffect(() => {
    if (!id) return;
    Promise.all([
      AnalyticsAPI.getDashboard(id),
      AnalyticsAPI.listDatasets(),
    ]).then(([d, dsList]) => {
      setDashboard(d);
      setDatasets(dsList ?? []);
      try {
        const canvas = (typeof d.canvas === 'string' ? JSON.parse(d.canvas as any) : d.canvas) ?? DEFAULT_CANVAS;
        const widgets = (typeof d.spec === 'string' ? JSON.parse(d.spec as any) : d.spec) ?? [];
        setSpec({ canvas, widgets });
      } catch (e) {
        message.warning('解析大屏 spec 失败，使用默认画布');
        setSpec({ canvas: DEFAULT_CANVAS, widgets: [] });
      }
    }).catch((e) => message.error('加载大屏失败：' + (e as Error).message))
      .finally(() => setLoading(false));
  }, [id]);

  const selectedNode = useMemo(
    () => spec.widgets.find((w) => w.id === selectedId) ?? null,
    [spec.widgets, selectedId],
  );

  const handleAddWidget = (type: WidgetType) => {
    const def = WIDGET_REGISTRY[type];
    if (!def) return;
    const newNode: WidgetNode = {
      id: `w_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
      type,
      layout: {
        ...(def.defaults.layout ?? { x: 0, y: 0, w: 8, h: 6, z: 1 }),
        x: Math.floor((spec.widgets.length * 8) % 48),
        y: Math.max(0, ...spec.widgets.map((w) => w.layout.y + w.layout.h)),
      },
      data: def.defaults.data ?? { dimensions: [], measures: [] },
      title: def.defaults.title,
    };
    setSpec((s) => ({ ...s, widgets: [...s.widgets, newNode] }));
    setSelectedId(newNode.id);
  };

  const handleUpdateNode = (updated: WidgetNode) => {
    setSpec((s) => ({
      ...s,
      widgets: s.widgets.map((w) => (w.id === updated.id ? updated : w)),
    }));
  };

  const handleRemoveNode = () => {
    if (!selectedId) return;
    setSpec((s) => ({ ...s, widgets: s.widgets.filter((w) => w.id !== selectedId) }));
    setSelectedId(null);
  };

  const handleSave = async () => {
    if (!id || !dashboard) return;
    setSaving(true);
    try {
      const updated = await AnalyticsAPI.saveDashboard(id, {
        name: dashboard.name,
        description: dashboard.description,
        canvas: spec.canvas,
        spec: spec.widgets as any,
        expectedVersion: dashboard.version,
      });
      setDashboard(updated);
      message.success(`已保存（v${updated.version}）`);
    } catch (e) {
      message.error('保存失败：' + (e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handlePublish = async () => {
    // 先保存草稿，再开发布对话框（确保 spec 是最新的）
    if (!id || !dashboard) { setPublishOpen(true); return; }
    setSaving(true);
    try {
      const updated = await AnalyticsAPI.saveDashboard(id, {
        name: dashboard.name,
        description: dashboard.description,
        canvas: spec.canvas,
        spec: spec.widgets as any,
        expectedVersion: dashboard.version,
      });
      setDashboard(updated);
      setPublishOpen(true);
    } catch (e) {
      message.error('保存失败：' + (e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  // 钻取联动：从 spec.widgets[].events 收集所有 targetVar，自动补全到 globalVars（避免前端漏配）
  const globalVars: GlobalVar[] = useMemo(() => {
    const collected = new Map<string, GlobalVar>();
    spec.widgets.forEach((w) => {
      w.events?.forEach((e) => {
        if (e.type === 'filter' && e.targetVar) {
          if (!collected.has(e.targetVar)) {
            collected.set(e.targetVar, { key: e.targetVar, label: e.targetVar, source: 'widget' });
          }
        }
      });
    });
    // 合并 spec 中手动声明的 globalVars（label 优先用用户写的）
    spec.globalVars?.forEach((v) => collected.set(v.key, v));
    return Array.from(collected.values());
  }, [spec.widgets, spec.globalVars]);

  const handleCanvasChange = (newSpec: ScreenSpec) => setSpec(newSpec);

  if (loading || !dashboard) {
    return <div style={{ padding: 48, textAlign: 'center' }}><Spin tip="加载大屏..." /></div>;
  }

  return (
    <LinkageProvider initialVars={globalVars}>
    <Layout style={{ height: 'calc(100vh - 64px)', background: '#0a1420' }}>
      <Header style={{
        background: '#0e1d2f', padding: '8px 16px', height: 56, lineHeight: '40px',
        borderBottom: '1px solid #1f2d3d', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/analytics/dashboards')} type="text" style={{ color: '#eee' }}>
            返回
          </Button>
          <Input
            value={dashboard.name}
            onChange={(e) => setDashboard({ ...dashboard, name: e.target.value })}
            style={{ width: 280, background: '#15243a', color: '#eee', borderColor: '#243549' }}
          />
          <span style={{ color: '#666', fontSize: 12 }}>v{dashboard.version}</span>
        </Space>
        <Space>
          <span style={{ color: '#aaa', fontSize: 12 }}>画布:</span>
          <Select
            value={`${spec.canvas.width}×${spec.canvas.height}`}
            onChange={(v) => {
              const [w, h] = v.split('×').map(Number);
              setSpec({ ...spec, canvas: { ...spec.canvas, width: w, height: h } });
            }}
            style={{ width: 130 }}
            options={[
              { label: '1920 × 1080', value: '1920×1080' },
              { label: '2560 × 1440', value: '2560×1440' },
              { label: '1366 × 768', value: '1366×768' },
            ]}
          />
          <Button icon={<SaveOutlined />} loading={saving} onClick={handleSave} type="primary" ghost disabled={readOnly}>
            保存
          </Button>
          <Button icon={<SendOutlined />} onClick={handlePublish} type="primary" disabled={readOnly}>
            发布
          </Button>
          <Button icon={<EyeOutlined />} onClick={() => navigate(`/analytics/dashboards/${id}/view`)}>预览</Button>
        </Space>
      </Header>

      <Layout>
        {!readOnly && (
          <Sider width={240} theme="dark" style={{ background: '#0e1d2f', overflowY: 'auto' }}>
            <Palette onAdd={handleAddWidget} />
          </Sider>
        )}

        <Content style={{ overflow: 'auto', padding: 24, background: '#0a1420' }}>
          <ScreenCanvas
            spec={spec}
            selectedId={selectedId ?? undefined}
            onChange={handleCanvasChange}
            onSelect={setSelectedId}
          />
          <div style={{ height: 16 }} />
          <GlobalFilterBar vars={globalVars} />
        </Content>

        {!readOnly && (
          <RightSider width={360} theme="dark" style={{ background: '#0e1d2f', overflowY: 'auto' }}>
            <Inspector
              node={selectedNode}
              spec={spec}
              datasets={datasets}
              onChange={handleUpdateNode}
              onRemove={handleRemoveNode}
            />
          </RightSider>
        )}
      </Layout>

      <PublishDialog
        open={publishOpen}
        dashboardId={id}
        spec={spec}
        onClose={() => setPublishOpen(false)}
      />
    </Layout>
    </LinkageProvider>
  );
}

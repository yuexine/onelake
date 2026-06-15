/**
 * 全局搜索浮层（⌘K）（对应原型 §1.4 / §8.1.1）。
 * 跨资产/任务/API/审批/告警 5 个域搜索；结果点击带上下文跳转。
 */
import { Modal, Input, Tabs, List, Typography, Tag, Empty } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores/app';
import { ClassificationBadge, StatusBadge } from '../components';
import { lakehouseAssets, syncTasks, apis, approvals, opsAlerts, searchHot, searchRecent } from '../mock';

const { Text } = Typography;

interface ResultItem {
  key: string;
  title: string;
  desc: string;
  meta?: React.ReactNode;
  link: string;
}

export function GlobalSearch() {
  const { searchOpen, setSearchOpen } = useAppStore();
  const [q, setQ] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setSearchOpen(!useAppStore.getState().searchOpen);
      }
      if (e.key === 'Escape') setSearchOpen(false);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [setSearchOpen]);

  const groups = useMemo(() => {
    const kw = q.toLowerCase();
    const match = (s: string) => !kw || s.toLowerCase().includes(kw);
    const assets: ResultItem[] = lakehouseAssets
      .filter((a) => match(a.fqn) || match(a.name) || match(a.description || ''))
      .map((a) => ({
        key: a.id, title: a.fqn,
        desc: a.description || '',
        meta: <><ClassificationBadge level={a.classification} /><Tag>质量 {a.qualityScore}</Tag><Tag>订阅 {a.popularity}</Tag></>,
        link: `/catalog/assets/${a.id}`,
      }));
    const tasks: ResultItem[] = syncTasks
      .filter((t) => match(t.name) || match(t.targetTable))
      .map((t) => ({
        key: t.id, title: t.name, desc: `${t.mode} → ${t.targetTable}`,
        meta: <StatusBadge status={t.status} />,
        link: `/integration/sync-tasks/${t.id}`,
      }));
    const apiList: ResultItem[] = apis
      .filter((p) => match(p.apiPath) || match(p.name))
      .map((p) => ({
        key: p.id, title: p.apiPath, desc: p.name,
        meta: <><StatusBadge status={p.status} /><Tag>v{p.currentVersion}</Tag><Tag>{p.qps} QPS</Tag></>,
        link: `/dataservice/apis/${p.id}`,
      }));
    const apv: ResultItem[] = approvals
      .filter((p) => match(p.targetRef) || match(p.applicantName))
      .map((p) => ({
        key: p.id, title: p.targetRef, desc: `${p.applicantName} · ${p.reason || ''}`,
        meta: <StatusBadge status={p.status} />,
        link: `/system/approvals/${p.id}`,
      }));
    const al: ResultItem[] = opsAlerts
      .filter((a) => match(a.title) || match(a.source))
      .map((a) => ({
        key: a.id, title: a.title, desc: a.source,
        meta: <Tag color={a.level === 'P0' ? 'red' : a.level === 'P1' ? 'orange' : 'default'}>{a.level}</Tag>,
        link: `/monitor/alerts/${a.id}`,
      }));
    return { assets, tasks, apis: apiList, approvals: apv, alerts: al };
  }, [q]);

  const total = Object.values(groups).reduce((s, l) => s + l.length, 0);

  const renderItem = (it: ResultItem) => (
    <List.Item style={{ cursor: 'pointer' }} onClick={() => { navigate(it.link); setSearchOpen(false); setQ(''); }}>
      <List.Item.Meta title={<Text strong>{it.title}</Text>} description={<span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>{it.meta}<Text type="secondary">{it.desc}</Text></span>} />
    </List.Item>
  );

  return (
    <Modal
      open={searchOpen}
      onCancel={() => setSearchOpen(false)}
      footer={null}
      width={720}
      styles={{ body: { padding: 0 } }}
      title={null}
    >
      <Input size="large" prefix={<SearchOutlined />} placeholder="搜索资产/任务/API/审批/告警…" value={q}
        onChange={(e) => setQ(e.target.value)} autoFocus bordered={false} style={{ padding: 16 }} />
      {!q && (
        <div style={{ padding: '0 16px 16px' }}>
          <Text type="secondary">最近访问</Text>
          <div style={{ marginTop: 8 }}>
            {searchRecent.map((s) => (
              <Tag key={s} style={{ cursor: 'pointer' }} onClick={() => setQ(s)}>{s}</Tag>
            ))}
          </div>
          <div style={{ marginTop: 12 }}>
            <Text type="secondary">热门搜索</Text>
            <div style={{ marginTop: 8 }}>
              {searchHot.map((s) => (
                <Tag key={s} color="blue" style={{ cursor: 'pointer' }} onClick={() => setQ(s)}>{s}</Tag>
              ))}
            </div>
          </div>
        </div>
      )}
      {q && total === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`未找到 "${q}"，可扩大检索范围`} style={{ padding: 40 }} />}
      {q && total > 0 && (
        <Tabs
          defaultActiveKey="assets"
          style={{ padding: '0 16px' }}
          items={[
            { key: 'assets', label: `资产 (${groups.assets.length})`, children: <List size="small" dataSource={groups.assets} renderItem={renderItem} /> },
            { key: 'tasks', label: `任务 (${groups.tasks.length})`, children: <List size="small" dataSource={groups.tasks} renderItem={renderItem} /> },
            { key: 'apis', label: `API (${groups.apis.length})`, children: <List size="small" dataSource={groups.apis} renderItem={renderItem} /> },
            { key: 'approvals', label: `审批 (${groups.approvals.length})`, children: <List size="small" dataSource={groups.approvals} renderItem={renderItem} /> },
            { key: 'alerts', label: `告警 (${groups.alerts.length})`, children: <List size="small" dataSource={groups.alerts} renderItem={renderItem} /> },
          ]}
        />
      )}
    </Modal>
  );
}

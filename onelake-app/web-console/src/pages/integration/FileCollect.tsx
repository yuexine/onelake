/**
 * 文件采集配置（对应原型 §8.2.6 升级版）。
 */
import {
  Table, Tag, Progress, Space, Switch, Form, Input, Select, Button, message, Tooltip,
  Typography,
} from 'antd';
import {
  FileTextOutlined, SettingOutlined, CloudServerOutlined, CheckCircleOutlined,
  WarningOutlined, StopOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useState } from 'react';
import {
  PageHeader, SectionCard,
} from '../../components';
import { fileWatchList } from '../../mock';

const { Text } = Typography;

const STATUS_META: Record<string, { color: string; bg: string; icon: React.ReactNode; label: string }> = {
  '已入湖':    { color: 'var(--ol-success)', bg: 'var(--ol-success-soft)', icon: <CheckCircleOutlined />, label: '已入湖' },
  '重传中':    { color: 'var(--ol-warning)', bg: 'var(--ol-warning-soft)', icon: <WarningOutlined />,    label: '重传中' },
  '去重跳过':  { color: 'var(--ol-ink-3)',   bg: 'var(--ol-fill-soft)',     icon: <StopOutlined />,        label: '去重跳过' },
};

const CHECKSUM_META: Record<string, { color: string; soft: string; border: string; label: string }> = {
  matched:   { color: 'var(--ol-success)', soft: 'var(--ol-success-soft)', border: '#BBF7D0', label: '✓ 一致' },
  mismatch:  { color: 'var(--ol-error)',   soft: 'var(--ol-error-soft)',   border: '#FCA5A5', label: '✗ 不符' },
  '-':       { color: 'var(--ol-ink-3)',   soft: 'var(--ol-fill-soft)',    border: 'var(--ol-line-soft)', label: '未校验' },
};

function splitFileName(filename: string) {
  const index = filename.lastIndexOf('.');
  if (index <= 0) return { stem: filename, ext: '' };
  return { stem: filename.slice(0, index), ext: filename.slice(index) };
}

export default function FileCollect() {
  const [activeTab, setActiveTab] = useState<'list' | 'config'>('list');

  return (
    <div className="ol-page">
      <PageHeader
        icon={<FileTextOutlined />}
        title="文件采集"
        subtitle={<span className="ol-chip">数据集成 · L1-3</span>}
        description="SFTP / FTP / NAS / S3 监听，分片上传 + MD5 校验 + 内容去重，保证文件完整才入湖"
        actions={
          <>
            <Button icon={<ReloadOutlined />} onClick={() => message.success('已刷新')}>刷新</Button>
            <Button type="primary" icon={<SettingOutlined />} onClick={() => setActiveTab('config')}>采集配置</Button>
          </>
        }
      />

      {/* 切换 tab */}
      <div style={{ display: 'flex', gap: 6 }}>
        {(['list', 'config'] as const).map((key) => (
          <button
            key={key}
            onClick={() => setActiveTab(key)}
            style={{
              padding: '6px 14px', borderRadius: 6, border: '1px solid transparent',
              background: activeTab === key ? 'var(--ol-brand)' : 'transparent',
              color: activeTab === key ? '#fff' : 'var(--ol-ink-2)',
              cursor: 'pointer', fontSize: 13, fontWeight: 500,
              transition: 'all var(--ol-dur-fast) var(--ol-ease)',
            }}
          >
            {key === 'list' ? '已监听文件' : '采集配置'}
          </button>
        ))}
      </div>

      {activeTab === 'list' && (
        <SectionCard
          title="已监听文件"
          icon={<FileTextOutlined />}
          subtitle="来源：S3 · 监听目录 /data/inbound/orders/"
          flatBody
        >
          <Table
            size="middle"
            rowKey="id"
            dataSource={fileWatchList}
            pagination={false}
            columns={[
              { title: '文件名', dataIndex: 'filename', width: 420, render: (f: string) => {
                const { stem, ext } = splitFileName(f);
                return (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 0 }}>
                    <div style={{
                      width: 28, height: 28, borderRadius: 6, background: 'var(--ol-brand-soft)',
                      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                      color: 'var(--ol-brand)', fontSize: 14, flexShrink: 0,
                    }}>
                      <FileTextOutlined />
                    </div>
                    <div style={{ minWidth: 0 }}>
                      <Tooltip title={f}>
                        <span
                          className="mono ol-truncate"
                          style={{
                            maxWidth: 320,
                            display: 'inline-flex',
                            alignItems: 'baseline',
                            padding: '1px 7px',
                            borderRadius: 4,
                            border: '1px solid var(--ol-line-soft)',
                            background: 'var(--ol-card)',
                            color: 'var(--ol-ink)',
                            fontSize: 13,
                            fontWeight: 600,
                            lineHeight: '18px',
                          }}
                        >
                          <span className="ol-truncate">{stem}</span>
                          {ext && <span style={{ color: 'var(--ol-ink-3)', fontWeight: 500 }}>{ext}</span>}
                        </span>
                      </Tooltip>
                      <div style={{ marginTop: 2, fontSize: 11, lineHeight: '15px', color: 'var(--ol-ink-3)' }}>
                        CSV 文件 · /data/inbound/orders/
                      </div>
                    </div>
                  </div>
                );
              } },
              { title: '大小 (MB)', dataIndex: 'sizeMb', align: 'right' as const, render: (v: number) => (
                <span className="mono tnum" style={{ fontSize: 12 }}>{v.toLocaleString()}</span>
              ) },
              { title: 'MD5 校验', dataIndex: 'checksum', render: (c: string) => {
                const m = CHECKSUM_META[c] || CHECKSUM_META['-'];
                return (
                  <span style={{
                    padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                    background: m.soft, color: m.color,
                    border: `1px solid ${m.border}`,
                  }}>{m.label}</span>
                );
              } },
              { title: '分片进度', dataIndex: 'progress', width: 200, render: (p: number) => (
                <Progress
                  percent={p} size="small"
                  strokeColor={p === 100 ? 'var(--ol-success)' : p === 0 ? 'var(--ol-ink-4)' : 'var(--ol-brand)'}
                  style={{ margin: 0 }}
                />
              ) },
              { title: '状态', dataIndex: 'status', render: (s: string) => {
                const m = STATUS_META[s] || STATUS_META['已入湖'];
                return (
                  <span style={{
                    display: 'inline-flex', alignItems: 'center', gap: 4,
                    padding: '2px 8px', borderRadius: 4, fontSize: 12, fontWeight: 500,
                    background: m.bg, color: m.color,
                  }}>
                    {m.icon} {m.label}
                  </span>
                );
              } },
            ]}
          />
        </SectionCard>
      )}

      {activeTab === 'config' && (
        <SectionCard title="采集配置" icon={<SettingOutlined />}>
          <Form layout="vertical" style={{ maxWidth: 720 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <Form.Item label="来源类型"><Select defaultValue="SFTP" options={['SFTP', 'FTP', 'NAS', 'S3'].map((v) => ({ label: v, value: v }))} /></Form.Item>
              <Form.Item label="监听方式"><Select defaultValue="event" options={[
                { label: '事件通知 (S3→SQS)', value: 'event' },
                { label: '定时轮询 5 分钟', value: 'poll' },
              ]} /></Form.Item>
            </div>
            <Form.Item label="监听目录"><Input placeholder="/data/inbound/orders/" /></Form.Item>
            <Form.Item label="文件匹配"><Input placeholder="orders_*.csv" /></Form.Item>
            <Form.Item label="稳定判定" tooltip="避免半文件入湖">
              <Tag color="processing" style={{ padding: '4px 10px' }}>大小连续 2 次不变才采集</Tag>
            </Form.Item>

            <div style={{
              padding: 16, borderRadius: 8,
              background: 'var(--ol-fill-soft)',
              border: '1px solid var(--ol-line-soft)',
            }}>
              <div style={{ fontSize: 12, color: 'var(--ol-ink-3)', marginBottom: 10 }}>文件处理策略</div>
              <Space direction="vertical" size={10}>
                <Space><Switch defaultChecked /> 大文件分片上传 (64MB/片) + 断点续传</Space>
                <Space><Switch defaultChecked /> MD5/SHA-256 校验，不一致自动重传</Space>
                <Space><Switch defaultChecked /> 按内容哈希去重，命中已采则跳过</Space>
              </Space>
            </div>

            <div style={{ marginTop: 16 }}>
              <Button type="primary" onClick={() => message.success('已保存')}>保存配置</Button>
            </div>
          </Form>
        </SectionCard>
      )}
    </div>
  );
}

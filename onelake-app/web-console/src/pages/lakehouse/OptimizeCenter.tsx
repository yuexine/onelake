/**
 * 存储优化中心（对应原型 §8.3.7 升级版）。
 */
import { Row, Col, Table, Tag, Space, Button, Progress, message, Typography } from 'antd';
import { useState } from 'react';
import {
  ThunderboltOutlined, FileSearchOutlined, HddOutlined,
  ReloadOutlined, CloudOutlined,
} from '@ant-design/icons';
import { optimizeSuggestions } from '../../mock';
import { PageHeader, SectionCard, StatCard, useAsyncAction, DangerConfirm } from '../../components';

const { Text } = Typography;

export default function OptimizeCenter() {
  const [batchOpen, setBatchOpen] = useState(false);
  const { run, isLoading } = useAsyncAction();
  return (
    <div className="ol-page">
      <PageHeader
        icon={<ThunderboltOutlined />}
        title="存储优化中心"
        subtitle={<span className="ol-chip">湖仓 · L2-3</span>}
        description="自动检测小文件、孤儿文件、冷数据，给出优化建议与进度跟踪"
        actions={
          <Button
            type="primary" icon={<ThunderboltOutlined />}
            onClick={() => setBatchOpen(true)}
          >
            批量优化
          </Button>
        }
      />

      <div className="ol-grid-stats">
        <StatCard icon={<ThunderboltOutlined />} intent="warning" label="待优化表" value={18} suffix="张" hint="含小文件/孤儿文件/冷数据" />
        <StatCard icon={<FileSearchOutlined />} intent="error" label="孤儿文件" value={23000} suffix="个" hint="待清理" />
        <StatCard icon={<HddOutlined />} intent="info" label="冷数据可下沉" value={1.2} suffix="TB" hint="下沉 Glacier 节省 60%" />
        <StatCard icon={<ReloadOutlined />} intent="success" label="本周已优化" value={42} suffix="张" hint="释放 320 GB" />
      </div>

      <SectionCard title="优化建议" icon={<ThunderboltOutlined />} subtitle={`${optimizeSuggestions.length} 条建议`} flatBody>
        <Table
          size="middle"
          rowKey="table"
          dataSource={optimizeSuggestions}
          pagination={false}
          columns={[
            { title: '表', dataIndex: 'table', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '小文件数', dataIndex: 'smallFiles', align: 'right' as const, render: (v: number) => v ? (
              <Tag color="warning" style={{ margin: 0 }}>{v.toLocaleString()}</Tag>
            ) : '-' },
            { title: '状态', dataIndex: 'status', render: (s: string) => (
              <span style={{
                padding: '2px 8px', borderRadius: 4, fontSize: 11, fontWeight: 600,
                background: s.includes('冷') ? 'var(--ol-info-soft)' : s.includes('正常') ? 'var(--ol-success-soft)' : 'var(--ol-warning-soft)',
                color: s.includes('冷') ? '#0369A1' : s.includes('正常') ? 'var(--ol-success)' : '#B45309',
              }}>{s}</span>
            ) },
            { title: '建议', dataIndex: 'suggestion' },
            { title: '操作', width: 120, render: (_: unknown, r: any) => (
              <Button type="primary" size="small" ghost
                loading={isLoading(`opt-${r.table}`)}
                onClick={() => run(`opt-${r.table}`, async () => {
                  await new Promise((resolve) => setTimeout(resolve, 600));
                }, { successMsg: `${r.suggestion} 已触发`, duration: 2.5 })}
                icon={<ThunderboltOutlined />}>{r.action}</Button>
            ) },
          ]}
        />
      </SectionCard>

      <SectionCard title="优化任务进度" icon={<ReloadOutlined />}>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {[
            { name: 'dwd_order_df Compaction', percent: 80, color: 'var(--ol-brand)' },
            { name: 'dwd_user_df 小文件合并', percent: 45, color: 'var(--ol-info)' },
            { name: '冷数据下沉 Glacier', percent: 30, color: '#7C3AED' },
          ].map((t) => (
            <div key={t.name}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <Space size={8}>
                  <CloudOutlined style={{ color: t.color }} />
                  <Text style={{ fontSize: 13, fontWeight: 500 }}>{t.name}</Text>
                </Space>
                <span className="mono tnum" style={{ fontSize: 12, color: 'var(--ol-ink-3)' }}>{t.percent}%</span>
              </div>
              <Progress
                percent={t.percent}
                size="small"
                strokeColor={t.color}
                showInfo={false}
                style={{ margin: 0 }}
              />
            </div>
          ))}
        </Space>
      </SectionCard>

      <DangerConfirm
        open={batchOpen}
        title={`批量触发 ${optimizeSuggestions.length} 条优化建议`}
        description="将启动 Compaction / 冷数据下沉 / 排序等操作，过程中可能短暂影响读取性能。"
        impacts={[
          { label: '优化任务', value: optimizeSuggestions.length },
          { label: '影响范围', value: '全租户' },
          { label: '预计耗时', value: '数十分钟' },
        ]}
        impactLevel="MEDIUM"
        confirmName="批量优化"
        okText="确认触发"
        okType="primary"
        onCancel={() => setBatchOpen(false)}
        onConfirm={() => run('batch-optimize', async () => {
          await new Promise((r) => setTimeout(r, 1000));
          setBatchOpen(false);
        }, {
          successMsg: `已批量触发 ${optimizeSuggestions.length} 条优化建议`,
          errorMsg: '批量优化触发失败，请重试',
          duration: 3,
        })}
      />
    </div>
  );
}

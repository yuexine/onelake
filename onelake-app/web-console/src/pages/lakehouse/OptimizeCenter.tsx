/**
 * 存储优化中心（对应原型 §8.3.7 升级版）。
 */
import { Alert, App as AntdApp, Button, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  ThunderboltOutlined, FileSearchOutlined, HddOutlined,
  ReloadOutlined, CloudOutlined,
} from '@ant-design/icons';
import { PageHeader, SectionCard, StatCard, StateView, useAsyncAction, DangerConfirm } from '../../components';
import { CatalogAPI } from '../../api';
import type { AssetMaintenanceAssessment, AssetMaintenanceOperation } from '../../types';

const { Text } = Typography;

const operationLabels: Record<AssetMaintenanceOperation, string> = {
  OPTIMIZE: 'Compaction',
  EXPIRE_SNAPSHOTS: '清理快照',
  REMOVE_ORPHAN_FILES: '清理孤儿文件',
};

function fmtBytes(value?: number) {
  if (value == null) return '-';
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GB`;
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${value} B`;
}

function statusColor(status: string) {
  if (status === 'OK') return 'success';
  if (status === 'CRITICAL') return 'error';
  return 'warning';
}

export default function OptimizeCenter() {
  const { message } = AntdApp.useApp();
  const [batchOpen, setBatchOpen] = useState(false);
  const [assessments, setAssessments] = useState<AssetMaintenanceAssessment[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [runningKey, setRunningKey] = useState<string | null>(null);
  const { run, isLoading } = useAsyncAction();

  const loadAssessments = async () => {
    setLoading(true);
    setLoadError(null);
    try {
      setAssessments(await CatalogAPI.listMaintenance());
    } catch (e) {
      setAssessments([]);
      setLoadError(e instanceof Error ? e.message : '存储优化状态加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadAssessments();
  }, []);

  const stats = useMemo(() => {
    const pending = assessments.filter((item) => item.status !== 'OK').length;
    const smallFiles = assessments.reduce((sum, item) => sum + (item.smallFileCount || 0), 0);
    const totalBytes = assessments.reduce((sum, item) => sum + (item.totalBytes || 0), 0);
    const freshnessBreached = assessments.filter((item) => item.freshnessStatus === 'BREACHED').length;
    return { pending, smallFiles, totalBytes, freshnessBreached };
  }, [assessments]);

  const batchTargets = assessments.filter((item) => item.suggestedOperations.includes('OPTIMIZE'));

  const triggerMaintenance = async (item: AssetMaintenanceAssessment, operation: AssetMaintenanceOperation) => {
    const key = `${item.assetId}-${operation}`;
    setRunningKey(key);
    try {
      const result = await CatalogAPI.runMaintenance(item.assetId, operation);
      message.success(result.message);
      await loadAssessments();
    } catch (e) {
      message.error(e instanceof Error ? e.message : `${operationLabels[operation]} 失败`);
    } finally {
      setRunningKey(null);
    }
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<ThunderboltOutlined />}
        title="存储优化中心"
        subtitle={<span className="ol-chip">湖仓 · L2-3</span>}
        description="自动检测小文件、孤儿文件、冷数据，给出优化建议与进度跟踪"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={loadAssessments} loading={loading}>刷新</Button>
            <Button
              type="primary" icon={<ThunderboltOutlined />}
              disabled={batchTargets.length === 0}
              loading={isLoading('batch-optimize')}
              onClick={() => setBatchOpen(true)}
            >
              批量 Compaction
            </Button>
          </Space>
        }
      />

      <div className="ol-grid-stats">
        <StatCard icon={<ThunderboltOutlined />} intent="warning" label="待优化表" value={stats.pending} suffix="张" hint="DWD 运维评估非 OK" />
        <StatCard icon={<FileSearchOutlined />} intent="error" label="小文件" value={stats.smallFiles} suffix="个" hint="低于阈值的 Iceberg data files" />
        <StatCard icon={<HddOutlined />} intent="info" label="DWD 数据量" value={fmtBytes(stats.totalBytes)} hint="来自 Iceberg $files" />
        <StatCard icon={<CloudOutlined />} intent="success" label="SLA 违约" value={stats.freshnessBreached} suffix="张" hint="DWD 新鲜度超过 1h" />
      </div>

      <SectionCard title="优化建议" icon={<ThunderboltOutlined />} subtitle={`${assessments.length} 张 DWD 表`} flatBody>
        {loadError && <Alert type="error" showIcon message="优化状态加载失败" description={loadError} style={{ marginBottom: 12 }} />}
        {loading ? (
          <StateView state="loading" rows={5} />
        ) : assessments.length === 0 ? (
          <StateView state="empty" title="暂无 DWD 运维对象" description="当前租户下还没有可评估的 DWD 资产" />
        ) : (
          <Table
            size="middle"
            rowKey="assetId"
            dataSource={assessments}
            pagination={false}
            columns={[
              { title: '表', dataIndex: 'fqn', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
              { title: '状态', dataIndex: 'status', width: 110, render: (v: string) => <Tag color={statusColor(v)}>{v}</Tag> },
              { title: '文件', dataIndex: 'fileCount', align: 'right' as const, render: (v?: number) => v ?? '-' },
              { title: '小文件', dataIndex: 'smallFileCount', align: 'right' as const, render: (v?: number) => v ? <Tag color="warning">{v}</Tag> : (v ?? '-') },
              { title: '大小', dataIndex: 'totalBytes', align: 'right' as const, render: (v?: number) => fmtBytes(v) },
              { title: '新鲜度', dataIndex: 'freshnessLagMinutes', render: (_: unknown, r) => (
                <Space size={6}>
                  <Tag color={r.freshnessStatus === 'BREACHED' ? 'error' : r.freshnessStatus === 'OK' ? 'success' : 'default'}>
                    {r.freshnessStatus}
                  </Tag>
                  <span className="mono tnum">{r.freshnessLagMinutes ?? '-'} / {r.freshnessSlaMinutes} min</span>
                </Space>
              ) },
              { title: '风险', dataIndex: 'risks', render: (risks: string[]) => risks.length ? risks.map((risk) => <Tag key={risk}>{risk}</Tag>) : '-' },
              { title: '操作', width: 240, render: (_: unknown, item) => {
                const operations = item.suggestedOperations.length ? item.suggestedOperations : (['OPTIMIZE'] as AssetMaintenanceOperation[]);
                return (
                  <Space wrap>
                    {operations.map((operation) => (
                      <Button
                        key={operation}
                        size="small"
                        type={operation === 'OPTIMIZE' ? 'primary' : 'default'}
                        ghost={operation === 'OPTIMIZE'}
                        icon={<ThunderboltOutlined />}
                        loading={runningKey === `${item.assetId}-${operation}`}
                        onClick={() => triggerMaintenance(item, operation)}
                      >
                        {operationLabels[operation]}
                      </Button>
                    ))}
                  </Space>
                );
              } },
            ]}
          />
        )}
      </SectionCard>

      <DangerConfirm
        open={batchOpen}
        title={`批量触发 ${batchTargets.length} 张 DWD 表 Compaction`}
        description="将对存在小文件风险的 DWD 表提交 Iceberg optimize 操作。"
        impacts={[
          { label: '优化任务', value: batchTargets.length },
          { label: '影响范围', value: 'DWD' },
          { label: '执行方式', value: 'Trino Iceberg ALTER TABLE EXECUTE' },
        ]}
        impactLevel="MEDIUM"
        confirmName="批量 Compaction"
        okText="确认触发"
        okType="primary"
        onCancel={() => setBatchOpen(false)}
        onConfirm={() => run('batch-optimize', async () => {
          for (const item of batchTargets) {
            await CatalogAPI.runMaintenance(item.assetId, 'OPTIMIZE');
          }
          setBatchOpen(false);
          await loadAssessments();
        }, {
          successMsg: `已批量触发 ${batchTargets.length} 张表 Compaction`,
          errorMsg: '批量 Compaction 触发失败，请重试',
          duration: 3,
        })}
      />
    </div>
  );
}

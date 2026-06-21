/**
 * 稽核结果 + 评分看板（对应原型 §8.5.2 升级版）。
 */
import { Row, Col, Progress, Table, Tag, Space, Typography, Select, Empty, message } from 'antd';
import { SafetyOutlined, CheckCircleOutlined, WarningOutlined } from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { PageHeader, SectionCard } from '../../components';
import { QualityAPI } from '../../api';
import type { QualityRule, QualityRunResult } from '../../types';
import { useEffect, useMemo, useState } from 'react';

const { Text } = Typography;

export default function QualityResults() {
  const [rules, setRules] = useState<QualityRule[]>([]);
  const [selectedRuleId, setSelectedRuleId] = useState<string>();
  const [results, setResults] = useState<QualityRunResult[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    QualityAPI.listRules()
      .then((items) => {
        setRules(items);
        setSelectedRuleId((current) => current || items[0]?.id);
      })
      .catch(() => message.error('质量规则加载失败'));
  }, []);

  useEffect(() => {
    if (!selectedRuleId) {
      setResults([]);
      return;
    }
    setLoading(true);
    QualityAPI.recentResults(selectedRuleId)
      .then(setResults)
      .catch(() => message.error('稽核结果加载失败'))
      .finally(() => setLoading(false));
  }, [selectedRuleId]);

  const selectedRule = rules.find((rule) => rule.id === selectedRuleId);
  const latestResult = results[0];
  const passRate = Number(latestResult?.passRate ?? 0);
  const sampleRows = latestResult?.sample || [];
  const trendData = useMemo(() => [...results].reverse(), [results]);

  const dims = [
    { name: '完整性', value: passRate, intent: passRate >= 95 ? 'var(--ol-success)' : 'var(--ol-warning)' },
    { name: '准确性', value: passRate, intent: passRate >= 95 ? 'var(--ol-success)' : 'var(--ol-warning)' },
    { name: '一致性', value: passRate, intent: passRate >= 95 ? 'var(--ol-success)' : 'var(--ol-warning)' },
    { name: '及时性', value: latestResult ? 100 : 0, intent: latestResult ? 'var(--ol-success)' : 'var(--ol-warning)' },
  ];

  const trendOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['通过率'], top: 0, textStyle: { color: '#64748B', fontSize: 12 } },
    grid: { left: 40, right: 30, top: 40, bottom: 30 },
    xAxis: { type: 'category' as const, data: trendData.map((t) => new Date(t.checkedAt).toLocaleString()), axisLine: { lineStyle: { color: '#E2E8F0' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    yAxis: { type: 'value' as const, min: 80, max: 100, splitLine: { lineStyle: { color: '#F1F5F9' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
    series: [
      { name: '通过率', type: 'line', smooth: true, data: trendData.map((t) => Number(t.passRate || 0)), itemStyle: { color: '#0F4FD8' }, symbol: 'none' },
    ],
  };

  return (
    <div className="ol-page">
      <PageHeader
        icon={<SafetyOutlined />}
        title={
          <Space size={8}>
            稽核结果
            <Text code style={{ fontSize: 13 }}>{selectedRule?.targetFqn || '请选择规则'}</Text>
          </Space>
        }
        subtitle={<span className="ol-chip">质量 · L3-4</span>}
        description="完整性 / 准确性 / 一致性 / 及时性 四维度评分"
        actions={(
          <Select
            style={{ minWidth: 320 }}
            placeholder="选择质量规则"
            value={selectedRuleId}
            onChange={setSelectedRuleId}
            options={rules.map((rule) => ({
              label: `${rule.targetFqn} / ${rule.targetColumn || '全表'} / ${rule.ruleType}`,
              value: rule.id,
            }))}
          />
        )}
      />

      <Row gutter={16}>
        <Col xs={24} lg={8}>
          <SectionCard title="整体通过率" icon={<SafetyOutlined />} style={{ height: '100%' }}>
            <div style={{ textAlign: 'center', padding: 12 }}>
              <Progress
                type="dashboard"
                percent={passRate}
                strokeColor={{ '0%': 'var(--ol-success)', '100%': 'var(--ol-brand)' }}
                trailColor="var(--ol-fill-soft)"
                format={(p) => <span className="tnum" style={{ fontSize: 18, fontWeight: 600, color: 'var(--ol-ink)' }}>{p}%</span>}
              />
              <div style={{ marginTop: 12, fontSize: 12, color: 'var(--ol-ink-3)' }}>
                失败 <Text strong style={{ color: latestResult?.failedRows ? 'var(--ol-error)' : 'var(--ol-success)' }}>{latestResult?.failedRows ?? 0}</Text> 行
              </div>
            </div>
          </SectionCard>
        </Col>
        <Col xs={24} lg={16}>
          <SectionCard title="多维度评分" icon={<SafetyOutlined />} subtitle="完整 / 准确 / 一致 / 及时 四维加权" style={{ height: '100%' }}>
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              {dims.map((d) => (
                <div key={d.name}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                    <Text style={{ fontSize: 13, fontWeight: 500 }}>{d.name}</Text>
                    <span className="mono tnum" style={{ fontSize: 13, fontWeight: 600, color: d.intent }}>{d.value}</span>
                  </div>
                  <Progress
                    percent={d.value}
                    showInfo={false}
                    strokeColor={d.intent}
                    trailColor="var(--ol-fill-soft)"
                    size="small"
                  />
                </div>
              ))}
            </Space>
          </SectionCard>
        </Col>
      </Row>

      <SectionCard title="质量分趋势（近 7 天）" icon={<SafetyOutlined />}>
        <ReactECharts option={trendOption} style={{ height: 280 }} />
      </SectionCard>

      <SectionCard title="异常行明细（抽样）" icon={<WarningOutlined />} flatBody>
        <Table
          size="middle"
          rowKey={(record) => `${record.row}-${record.column}-${record.value}`}
          dataSource={sampleRows}
          loading={loading}
          locale={{ emptyText: <Empty description="暂无异常样例" /> }}
          pagination={false}
          columns={[
            { title: '行号', dataIndex: 'row', render: (v: number) => <Text code>{v}</Text> },
            { title: '资产', dataIndex: 'targetFqn', render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text> },
            { title: '字段', dataIndex: 'column', render: (v: string) => <span className="ol-chip">{v}</span> },
            { title: '异常值', dataIndex: 'value', render: (v: string) => <Tag color="error" style={{ margin: 0 }}>{v}</Tag> },
            { title: '规则', dataIndex: 'ruleType' },
          ]}
        />
      </SectionCard>
    </div>
  );
}

/**
 * 存储优化中心（对应原型 §8.3.7）。
 */
import { Card, Row, Col, Statistic, Table, Tag, Space, Button, Progress, message } from 'antd';
import { optimizeSuggestions } from '../../mock';

export default function OptimizeCenter() {
  return (
    <Card title="湖仓 / 存储优化中心" extra={<Button type="primary" onClick={() => message.success('已批量优化')}>批量优化</Button>}>
      <Row gutter={16}>
        <Col span={8}><Card><Statistic title="待优化表" value={18} valueStyle={{ color: '#fa541c' }} /></Card></Col>
        <Col span={8}><Card><Statistic title="孤儿文件" value={23000} suffix="个" /></Card></Col>
        <Col span={8}><Card><Statistic title="冷数据可下沉" value={1.2} suffix="TB" /></Card></Col>
      </Row>

      <Card title="优化建议" style={{ marginTop: 16 }}>
        <Table size="small" rowKey="table" dataSource={optimizeSuggestions}
          columns={[
            { title: '表', dataIndex: 'table' },
            { title: '小文件数', dataIndex: 'smallFiles', render: (v: number) => v ? <Tag color="warning">{v.toLocaleString()}</Tag> : '-' },
            { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s.includes('冷') ? 'blue' : s.includes('正常') ? 'success' : 'warning'}>{s}</Tag> },
            { title: '建议', dataIndex: 'suggestion' },
            { title: '操作', render: (_: unknown, r: any) => <Button type="primary" size="small" onClick={() => message.success(`${r.suggestion} 已触发`)}>{r.action}</Button> },
          ]} />
      </Card>

      <Card title="优化任务进度" style={{ marginTop: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <div><span>dwd_order_df Compaction</span><Progress percent={80} size="small" style={{ width: 400, display: 'inline-block', marginLeft: 12 }} /></div>
        </Space>
      </Card>
    </Card>
  );
}

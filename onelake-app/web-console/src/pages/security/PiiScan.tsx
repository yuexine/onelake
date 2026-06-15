/**
 * PII 识别（对应原型 §8.7.2）。
 */
import { Card, Table, Tag, Space, Button, Checkbox, message, Modal, Typography } from 'antd';
import { useState } from 'react';
import { piiScan } from '../../mock';
import { ClassificationBadge } from '../../components';

const { Text } = Typography;

export default function PiiScan() {
  const [selected, setSelected] = useState<string[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);

  return (
    <Card title="资产与安全 / PII 识别" extra={<Space>
      <Button>重新扫描</Button>
      <Button type="primary" disabled={selected.length === 0} onClick={() => setConfirmOpen(true)}>批量确认密级</Button>
    </Space>}>
      <Table rowKey="fqn" dataSource={piiScan} size="middle"
        rowSelection={{ selectedRowKeys: selected, onChange: (k) => setSelected(k as string[]) }}
        columns={[
          { title: '资产.字段', dataIndex: 'fqn' },
          { title: '识别类型', dataIndex: 'type' },
          { title: '置信度', dataIndex: 'confidence', render: (c: number) => <Tag color={c > 0.9 ? 'success' : c > 0.8 ? 'warning' : 'default'}>{(c * 100).toFixed(0)}%</Tag> },
          { title: '建议密级', dataIndex: 'suggestLevel', render: (l: string) => <ClassificationBadge level={l as any} /> },
          { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'confirmed' ? 'success' : 'processing'}>{s === 'confirmed' ? '已确认' : '待确认'}</Tag> },
          { title: '操作', render: (_: unknown, r: any) => <Space><Button size="small" type="link">确认</Button><Button size="small" type="link">忽略</Button></Space> },
        ]} />

      <Modal open={confirmOpen} onCancel={() => setConfirmOpen(false)} title="批量确认密级"
        onOk={() => { setConfirmOpen(false); setSelected([]); message.success('已确认，全站随动（采集脱敏 + 目录徽章 + API 返回脱敏）'); }}>
        <Text>将批量确认 {selected.length} 个字段的密级。</Text>
        <div style={{ marginTop: 12, padding: 12, background: '#fffbe6', borderRadius: 4 }}>
          <Text type="warning">⚠ 一处设定，全站随动：目录徽章、血缘节点描边、采集脱敏开关、DAG 脱敏算子默认值、API 返回脱敏将自动套用同一策略。</Text>
        </div>
      </Modal>
    </Card>
  );
}

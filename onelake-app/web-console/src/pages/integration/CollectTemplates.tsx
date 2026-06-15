/**
 * 采集任务模板库（对应原型 §8.2.8）。
 */
import { Card, Row, Col, Button, Typography, Modal, Form, Select, Checkbox, Space, message } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { collectTemplates } from '../../mock';

const { Text } = Typography;

export default function CollectTemplates() {
  const navigate = useNavigate();
  const [selected, setSelected] = useState<typeof collectTemplates[0] | null>(null);

  return (
    <Card title="数据集成 / 任务模板">
      <Row gutter={16}>
        {collectTemplates.map((t) => (
          <Col key={t.id} span={6}>
            <Card hoverable size="small" title={<Space><span style={{ fontSize: 24 }}>{t.icon}</span>{t.name}</Space>}
              onClick={() => setSelected(t)}>
              <Text type="secondary">{t.desc}</Text>
              <div style={{ marginTop: 8 }}><Text type="secondary">参数：</Text>{t.fields.join(' · ')}</div>
              <Button type="primary" block style={{ marginTop: 12 }}>使用</Button>
            </Card>
          </Col>
        ))}
        <Col span={6}>
          <Card hoverable size="small" style={{ textAlign: 'center', paddingTop: 30, paddingBottom: 30, borderStyle: 'dashed' }}
            onClick={() => message.info('新建自定义模板')}>
            <Text type="secondary">+ 自定义模板</Text>
          </Card>
        </Col>
      </Row>

      <Modal open={!!selected} title={selected ? `使用模板「${selected.name}」` : ''} onCancel={() => setSelected(null)} onOk={() => { setSelected(null); message.success('已批量生成 N 个采集任务'); navigate('/integration/sync-tasks'); }}>
        <Form layout="vertical">
          <Form.Item label="源连接"><Select options={[{ label: '订单库', value: 'ds-001' }].map((v: any) => ({ label: v, value: v }))} defaultValue="ds-001" /></Form.Item>
          <Form.Item label="目标层"><Select options={['ODS', 'DWD'].map((v: any) => ({ label: v, value: v }))} defaultValue="ODS" /></Form.Item>
          <Form.Item label="表范围">
            <Checkbox.Group options={['orders', 'order_items', 'users', 'payments'].map((v: any) => ({ label: v, value: v }))} defaultValue={['orders', 'order_items']} />
          </Form.Item>
          <Text type="secondary">将一键生成 N 个采集任务（可批量编辑调度）</Text>
        </Form>
      </Modal>
    </Card>
  );
}

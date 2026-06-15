/**
 * 算子市场（对应原型 §8.4.6 · 审查补全）。
 */
import { Card, Row, Col, Tag, Space, Button, Input, Typography, Modal, Descriptions, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useState } from 'react';

const { Text } = Typography;

const OPERATORS = [
  { id: 'op-1', category: '治理', name: '清洗去重', in: '表', out: '表', version: 'v2', scope: '内置' },
  { id: 'op-2', category: '脱敏', name: '掩码脱敏', in: '字段', out: '表', version: 'v1', scope: '内置' },
  { id: 'op-3', category: '加密', name: '字段加密', in: '字段', out: '表', version: 'v3', scope: '内置' },
  { id: 'op-4', category: '治理', name: 'MDM 主数据', in: '多源', out: '金标表', version: 'v1', scope: '租户私有' },
  { id: 'op-5', category: '脱敏', name: '保格加密(FPE)', in: '字段', out: '字段', version: 'v1', scope: '自定义' },
];

export default function OperatorMarket() {
  const [selected, setSelected] = useState<typeof OPERATORS[0] | null>(null);
  return (
    <Card title="数据开发 / 算子市场" extra={<Input.Search placeholder="搜索" prefix={<SearchOutlined />} style={{ width: 240 }} />}>
      <Space style={{ marginBottom: 16 }}>
        {['全部', '内置', '自定义', '租户私有'].map((c, i) => <Button key={c} type={i === 0 ? 'primary' : 'default'}>{c}</Button>)}
      </Space>
      <Row gutter={[16, 16]}>
        {OPERATORS.map((op) => (
          <Col key={op.id} span={6}>
            <Card hoverable size="small" title={<Space>{op.name}<Tag>{op.version}</Tag></Space>}
              onClick={() => setSelected(op)}>
              <Space direction="vertical">
                <Text>分类：<Tag color="blue">{op.category}</Tag></Text>
                <Text>输入：{op.in} → 输出：{op.out}</Text>
                <Tag>{op.scope}</Tag>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Modal open={!!selected} onCancel={() => setSelected(null)} title={selected?.name}
        footer={[<Button key="i" onClick={() => { setSelected(null); message.success('已安装/更新'); }}>安装</Button>, <Button key="u" type="primary" onClick={() => message.success('已使用')}>使用</Button>]}>
        {selected && (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="声明输入">{selected.in}</Descriptions.Item>
            <Descriptions.Item label="声明输出">{selected.out}</Descriptions.Item>
            <Descriptions.Item label="版本">{selected.version}</Descriptions.Item>
            <Descriptions.Item label="使用示例"><Text code>FROM ods.orders | clean(mask(phone)) | WRITE dws</Text></Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </Card>
  );
}

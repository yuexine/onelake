/**
 * Schema 变更审批详情（对应原型 §8.2.11 · 审查补全）。
 */
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Tag, Descriptions, Space, Button, Alert, Typography, message } from 'antd';
import { ArrowLeftOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { schemaChangeRequests } from '../../mock';
import { ImpactAnalysis } from '../../components';

const { Text } = Typography;

export default function SchemaChangeApproval() {
  const { id } = useParams();
  const navigate = useNavigate();
  const req = schemaChangeRequests.find((r) => r.id === id) || schemaChangeRequests[0];

  return (
    <Card title={<Space><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/integration/cdc')} />Schema 变更审批 / {req.table}</Space>}>
      <Alert type="warning" message={<Space><Tag color="red">破坏性</Tag><Text>{req.change}</Text></Space>} style={{ marginBottom: 16 }} />

      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label="变更 Diff"><Text code>- {req.change.replace('DROP COLUMN ', '')} (移除)</Text></Descriptions.Item>
        <Descriptions.Item label="兼容性">{req.compatible ? <Tag color="success">兼容</Tag> : <Tag color="error">破坏性</Tag>}</Descriptions.Item>
        <Descriptions.Item label="当前管道状态">{req.bufferStrategy}</Descriptions.Item>
      </Descriptions>

      <div style={{ marginTop: 16 }}>
        <ImpactAnalysis impact={{
          assets: req.impact.downstreamTables, tasks: req.impact.tasks, apis: req.impact.apis,
          blocking: !req.compatible, suggestion: req.compatible ? '兼容变更，可直接应用' : '通过 → 应用 → 通知下游 → 任务恢复；驳回 → 保持旧 schema',
        }} />
      </div>

      <div style={{ marginTop: 16 }}>
        <Space>
          <Button type="primary" icon={<CheckOutlined />} onClick={() => { message.success('已通过 → 应用变更 → 通知下游 → 恢复任务'); navigate('/integration/cdc'); }}>通过</Button>
          <Button danger icon={<CloseOutlined />} onClick={() => { message.success('已驳回 → 保持旧 schema'); navigate('/integration/cdc'); }}>驳回</Button>
          <Button onClick={() => message.info('要求人工字段映射')}>要求人工字段映射</Button>
        </Space>
      </div>
    </Card>
  );
}

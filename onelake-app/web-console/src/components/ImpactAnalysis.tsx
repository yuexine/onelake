/**
 * 影响分析面板（§2.4 / §3 跨模块连续性）。
 * 删除/下线/密级变更前展示下游受影响资产、任务、API、订阅方数量。
 */
import { List, Tag, Typography } from 'antd';
import { AlertOutlined } from '@ant-design/icons';

const { Text } = Typography;

interface Impact {
  assets?: string[];
  tasks?: string[];
  apis?: string[];
  subscribers?: number;
  blocking?: boolean;
  suggestion?: string;
}

interface Props {
  impact: Impact;
}

export function ImpactAnalysis({ impact }: Props) {
  const blocking = impact.blocking ?? (impact.apis && impact.apis.length > 0);
  return (
    <div style={{ padding: 12, border: '1px solid #ffd591', borderRadius: 8, background: '#fffbe6' }}>
      <div style={{ marginBottom: 8 }}>
        <AlertOutlined style={{ color: '#fa8c16' }} />
        <Text strong style={{ marginLeft: 8 }}>影响分析</Text>
        {blocking && <Tag color="red" style={{ marginLeft: 8 }}>阻断</Tag>}
      </div>
      {impact.assets && impact.assets.length > 0 && (
        <List size="small" header={<Text type="secondary">受影响资产</Text>}
          dataSource={impact.assets} renderItem={(i) => <List.Item>{i}</List.Item>} />
      )}
      {impact.tasks && impact.tasks.length > 0 && (
        <List size="small" header={<Text type="secondary">受影响任务</Text>}
          dataSource={impact.tasks} renderItem={(i) => <List.Item>{i}</List.Item>} />
      )}
      {impact.apis && impact.apis.length > 0 && (
        <List size="small" header={<Text type="secondary">受影响 API</Text>}
          dataSource={impact.apis} renderItem={(i) => <List.Item>{i}</List.Item>} />
      )}
      {(impact.subscribers ?? 0) > 0 && (
        <div style={{ marginTop: 8 }}>
          <Text>受影响订阅方：<Text strong>{impact.subscribers}</Text> 个</Text>
        </div>
      )}
      {impact.suggestion && (
        <div style={{ marginTop: 8, padding: 8, background: '#fff', borderRadius: 4 }}>
          <Text type="secondary">处理建议：</Text>
          <Text>{impact.suggestion}</Text>
        </div>
      )}
    </div>
  );
}

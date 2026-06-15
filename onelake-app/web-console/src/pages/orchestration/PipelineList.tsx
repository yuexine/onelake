/**
 * 流水线列表（对应原型 §4.4.1）。
 */
import { Card, Table, Tag, Space, Button, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { StatusBadge } from '../../components';

const pipelines = [
  { id: 'p-1', name: 'order_pipeline', env: 'prod', version: 3, status: 'ENABLED', lastRun: '2026-06-14 02:00', enabled: true },
  { id: 'p-2', name: 'user_dws', env: 'prod', version: 2, status: 'ENABLED', lastRun: '2026-06-14 02:00', enabled: true },
  { id: 'p-3', name: 'risk_score', env: 'dev', version: 1, status: 'DRAFT', lastRun: '-', enabled: false },
];

export default function PipelineList() {
  const navigate = useNavigate();
  return (
    <Card title="数据开发 / 流水线" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/orchestration/pipelines/new')}>新建流水线</Button>}>
      <Table size="middle" rowKey="id" dataSource={pipelines}
        columns={[
          { title: '名称', dataIndex: 'name', render: (n: string, r: any) => <a onClick={() => navigate(`/orchestration/pipelines/${r.id}`)}>{n}</a> },
          { title: '环境', dataIndex: 'env', render: (e: string) => <Tag color={e === 'prod' ? 'red' : 'blue'}>{e}</Tag> },
          { title: '版本', dataIndex: 'version', render: (v: number) => <Tag>v{v}</Tag> },
          { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
          { title: '最近运行', dataIndex: 'lastRun' },
          { title: '操作', render: (_: unknown, r: any) => <Space>
            <Button size="small" type="primary" onClick={() => message.success('已触发')}>触发</Button>
            <Button size="small" type="link" onClick={() => navigate(`/orchestration/pipelines/${r.id}`)}>打开画布</Button>
          </Space> },
        ]} />
    </Card>
  );
}

/**
 * API 市场（对应原型 §8.8.1）。
 */
import { Card, Table, Tag, Space, Button, Input, Select, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { apis } from '../../mock';
import { ClassificationBadge, StatusBadge } from '../../components';

export default function ApiMarket() {
  const navigate = useNavigate();
  return (
    <Card title="数据服务 / API 市场" extra={<Space>
      <Select defaultValue="all" options={[{ label: '我发布的', value: 'mine' }, { label: '我订阅的', value: 'subscribed' }, { label: '全部', value: 'all' }].map((v: any) => ({ label: v, value: v }))} />
      <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/dataservice/apis/new')}>构建 API</Button>
    </Space>}>
      <Space style={{ marginBottom: 16 }}>
        <Input.Search placeholder="API 名/路径" style={{ width: 260 }} />
        <Select placeholder="分类" style={{ width: 120 }} options={['交易', '用户', '风控'].map((v: any) => ({ label: v, value: v }))} />
        <Select placeholder="状态" style={{ width: 120 }} options={['PUBLISHED', 'DRAFT', 'DEPRECATED', 'OFFLINE'].map((v: any) => ({ label: v, value: v }))} />
        <Select placeholder="密级" style={{ width: 120 }} options={['L1', 'L2', 'L3', 'L4'].map((v: any) => ({ label: v, value: v }))} />
      </Space>

      <Table rowKey="id" dataSource={apis} size="middle"
        columns={[
          { title: '路径', dataIndex: 'apiPath', render: (v: string, r: any) => <a onClick={() => navigate(`/dataservice/apis/${r.id}`)}>{v} <Tag color="blue">v{r.currentVersion}</Tag></a> },
          { title: '名称', dataIndex: 'name' },
          { title: '状态', dataIndex: 'status', render: (s: string) => <StatusBadge status={s} /> },
          { title: 'QPS 限制', dataIndex: 'qpsLimit' },
          { title: '实际 QPS', dataIndex: 'qps', render: (v?: number) => v ?? '-' },
          { title: '成功率', dataIndex: 'successRate', render: (v?: number) => v ? <Tag color={v > 99 ? 'success' : 'warning'}>{v}%</Tag> : '-' },
          { title: '密级', dataIndex: 'classification', render: (c: string) => c ? <ClassificationBadge level={c as any} /> : '-' },
          { title: '订阅方', dataIndex: 'subscriberCount' },
          { title: '操作', render: (_: unknown, r: any) => <Button size="small" type="primary" disabled={r.status !== 'PUBLISHED'} onClick={() => message.success('已提交订阅申请')}>订阅</Button> },
        ]} />
    </Card>
  );
}

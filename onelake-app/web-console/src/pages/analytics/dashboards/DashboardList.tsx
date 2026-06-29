/**
 * 大屏列表（P1 占位 —— 仅列出现有大屏 + 创建空大屏；P2 在此基础上加入"进入设计器"）。
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Empty, List, Modal, Input, Space, Tag, message,
} from 'antd';
import { PlusOutlined, EditOutlined, ShareAltOutlined, DeleteOutlined } from '@ant-design/icons';
import { AnalyticsAPI, type AnalyticsDashboard } from '../../../api';

const STATUS_TAG: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PUBLISHED: { color: 'green', label: '已发布' },
  OFFLINE: { color: 'red', label: '已下线' },
};

export default function DashboardList() {
  const navigate = useNavigate();
  const [data, setData] = useState<AnalyticsDashboard[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');

  const reload = async () => {
    setLoading(true);
    try {
      const list = await AnalyticsAPI.listDashboards();
      setData(list ?? []);
    } catch (e) {
      message.error('加载大屏失败：' + (e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { reload(); }, []);

  const handleCreate = async () => {
    if (!name.trim()) {
      message.warning('请输入大屏名称');
      return;
    }
    try {
      const d = await AnalyticsAPI.createDashboard(name.trim(), desc.trim());
      message.success('大屏已创建');
      setCreateOpen(false);
      setName(''); setDesc('');
      navigate(`/analytics/dashboards/${d.id}`);
    } catch (e) {
      message.error('创建失败：' + (e as Error).message);
    }
  };

  const handleDelete = (id: string, name: string) => {
    Modal.confirm({
      title: '删除大屏',
      content: `确认删除「${name}」？该操作不可恢复。`,
      okType: 'danger',
      onOk: async () => {
        await AnalyticsAPI.deleteDashboard(id);
        message.success('已删除');
        reload();
      },
    });
  };

  return (
    <div style={{ padding: 24 }}>
      <Card
        title="大屏中心"
        extra={
          <Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建大屏</Button>
          </Space>
        }
      >
        <List
          loading={loading}
          locale={{ emptyText: <Empty description="还没有大屏，点击右上角创建" /> }}
          dataSource={data}
          renderItem={(d) => {
            const tag = STATUS_TAG[d.status] ?? { color: 'default', label: d.status };
            return (
              <List.Item
                actions={[
                  <Button type="link" icon={<EditOutlined />} onClick={() => navigate(`/analytics/dashboards/${d.id}`)}>编辑</Button>,
                  <Button type="link" icon={<ShareAltOutlined />} disabled={d.status !== 'PUBLISHED'}
                          onClick={() => navigate(`/analytics/dashboards/${d.id}/view`)}>
                    预览
                  </Button>,
                  <Button type="link" danger icon={<DeleteOutlined />} onClick={() => handleDelete(d.id, d.name)} />,
                ]}
              >
                <List.Item.Meta
                  title={<Space>{d.name}<Tag color={tag.color}>{tag.label}</Tag><span style={{ color: '#999', fontSize: 12 }}>v{d.version}</span></Space>}
                  description={d.description ?? '（无描述）'}
                />
              </List.Item>
            );
          }}
        />
      </Card>

      <Modal
        title="新建大屏"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={handleCreate}
        okText="创建并进入设计器"
      >
        <Input placeholder="大屏名称（如：华东销售作战屏）" value={name} onChange={(e) => setName(e.target.value)} style={{ marginBottom: 12 }} />
        <Input.TextArea placeholder="描述（可选）" rows={3} value={desc} onChange={(e) => setDesc(e.target.value)} />
      </Modal>
    </div>
  );
}

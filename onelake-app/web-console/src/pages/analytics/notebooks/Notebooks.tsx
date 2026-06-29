/**
 * 分析工作台（P4a：列出 + 创建占位 Notebook + 启动 JupyterLab 入口）。
 */
import { useEffect, useState } from 'react';
import {
  Card, Button, Empty, Modal, Input, List, Tag, Space, message, Tooltip,
} from 'antd';
import { PlusOutlined, CodeOutlined, PlayCircleOutlined, LinkOutlined } from '@ant-design/icons';
import { http } from '../../../api/http';

interface NotebookItem {
  id: string;
  name: string;
  kernel: string;
  storagePath: string;
  createdAt?: string;
}

// P4a 简化：直接调 REST（避免 AnalyticsAPI 接口过早膨胀）；P4d 整合到 AnalyticsAPI
const NotebookAPI = {
  list: () => http.get('/analytics/notebooks') as Promise<NotebookItem[]>,
  create: (payload: { name: string; kernel?: string }) =>
    http.post('/analytics/notebooks', payload) as Promise<NotebookItem>,
  labUrl: (id: string) => http.get(`/analytics/notebooks/${id}/lab-url`) as Promise<{ url: string }>,
};

export default function Notebooks() {
  const [data, setData] = useState<NotebookItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState('');
  const [kernel, setKernel] = useState('python3');

  const reload = async () => {
    setLoading(true);
    try {
      const list = await NotebookAPI.list();
      setData(list ?? []);
    } catch (e) {
      // P4a 后端 endpoint 可能未启用（P3 编译时未挂接），降级提示
      setData([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { reload(); }, []);

  const handleCreate = async () => {
    if (!name.trim()) {
      message.warning('请输入 Notebook 名称');
      return;
    }
    try {
      await NotebookAPI.create({ name: name.trim(), kernel });
      message.success('Notebook 已创建');
      setCreateOpen(false);
      setName('');
      reload();
    } catch (e) {
      message.error('创建失败：' + (e as Error).message);
    }
  };

  const handleLaunch = async (id: string) => {
    try {
      const { url } = await NotebookAPI.labUrl(id);
      window.open(url, '_blank', 'noopener');
    } catch (e) {
      message.error('启动失败：' + (e as Error).message);
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <Card
        title="分析工作台"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建 Notebook
          </Button>
        }
      >
        <List
          loading={loading}
          locale={{ emptyText: <Empty description="还没有 Notebook，点击右上角创建" /> }}
          dataSource={data}
          renderItem={(nb) => (
            <List.Item
              actions={[
                <Button type="link" icon={<CodeOutlined />} onClick={() => handleLaunch(nb.id)}>
                  打开 JupyterLab
                </Button>,
                <Tooltip title="调度执行由 P4c 提供">
                  <Button type="link" icon={<PlayCircleOutlined />} disabled>
                    调度运行
                  </Button>
                </Tooltip>,
              ]}
            >
              <List.Item.Meta
                title={<Space>{nb.name}<Tag color="blue">{nb.kernel}</Tag></Space>}
                description={<Space><LinkOutlined /><code style={{ color: '#888' }}>{nb.storagePath}</code></Space>}
              />
            </List.Item>
          )}
        />
      </Card>

      <Modal
        title="新建 Notebook"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={handleCreate}
        okText="创建"
      >
        <Input placeholder="Notebook 名称（如：客户分群_RFM）" value={name} onChange={(e) => setName(e.target.value)} style={{ marginBottom: 12 }} />
        <Input placeholder="Kernel（python3 / pyspark）" value={kernel} onChange={(e) => setKernel(e.target.value)} />
      </Modal>
    </div>
  );
}

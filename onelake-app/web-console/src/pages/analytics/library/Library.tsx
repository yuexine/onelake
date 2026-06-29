/**
 * 组件 / 模板库（P4d 完整版）。
 *
 * 两个 Tab：
 * - 可视化组件：列出 WIDGET_REGISTRY 中所有组件类型（P2 已实现 15 个）
 * - 算法模板：从 TemplateAPI 拉取平台预置 + 租户自定义模板，"用此模板新建 Notebook"
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Empty, Tabs, List, Tag, Button, Space, message, Modal, Form, Input, Select, Row, Col,
} from 'antd';
import { ExperimentOutlined, ThunderboltOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { WIDGET_REGISTRY } from '../screen/registry';
import { TemplateAPI, type NotebookTemplate, type TemplateCategory } from '../../../api';

const CATEGORY_COLOR: Record<TemplateCategory, string> = {
  CLUSTERING: 'blue',
  REGRESSION: 'cyan',
  FORECAST: 'orange',
  CORRELATION: 'purple',
  EDA: 'geekblue',
  RFM: 'magenta',
};

export default function Library() {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<NotebookTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();

  const reload = async () => {
    setLoading(true);
    try {
      const list = await TemplateAPI.list();
      setTemplates(list ?? []);
    } catch (e) {
      // 后端 P4d 接口未挂时降级
      setTemplates([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { reload(); }, []);

  const handleCreate = async () => {
    try {
      const v = await form.validateFields();
      await TemplateAPI.create(v);
      message.success('模板已创建');
      setCreateOpen(false);
      form.resetFields();
      reload();
    } catch (e) {
      if ((e as any)?.errorFields) return;
      message.error('创建失败：' + (e as Error).message);
    }
  };

  const handleDelete = (id: string, name: string) => {
    Modal.confirm({
      title: '删除模板',
      content: `确认删除「${name}」？`,
      okType: 'danger',
      onOk: async () => {
        await TemplateAPI.delete(id);
        message.success('已删除');
        reload();
      },
    });
  };

  const widgetDefs = Object.values(WIDGET_REGISTRY);
  const groupLabel: Record<string, string> = {
    chart: '图表', metric: '指标 / 列表', media: '媒体', decoration: '装饰', embed: '嵌入',
  };

  return (
    <div style={{ padding: 24 }}>
      <Card title="组件 / 模板库">
        <Tabs
          items={[
            {
              key: 'widgets',
              label: <span><ThunderboltOutlined /> 可视化组件（{widgetDefs.length}）</span>,
              children: (
                <Row gutter={[12, 12]}>
                  {widgetDefs.map((w) => (
                    <Col key={w.type} xs={12} sm={8} md={6} lg={4}>
                      <Card size="small" hoverable bodyStyle={{ padding: 12, textAlign: 'center' }}>
                        <div style={{ fontSize: 24 }}>{w.icon}</div>
                        <div style={{ fontSize: 12, marginTop: 4 }}>{w.label}</div>
                        <Tag style={{ marginTop: 4, fontSize: 10 }}>{groupLabel[w.category] ?? w.category}</Tag>
                      </Card>
                    </Col>
                  ))}
                </Row>
              ),
            },
            {
              key: 'templates',
              label: <span><ExperimentOutlined /> 算法模板（{templates.length}）</span>,
              children: (
                <>
                  <Space style={{ marginBottom: 12, justifyContent: 'space-between', width: '100%' }}>
                    <span style={{ color: '#888' }}>
                      平台预置：KMeans · Prophet · 相关性 · RFM；用户自定义：通过 + 按钮
                    </span>
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                      上传自定义模板
                    </Button>
                  </Space>
                  <List
                    loading={loading}
                    grid={{ gutter: 12, xs: 1, sm: 2, md: 3, lg: 4 }}
                    dataSource={templates}
                    locale={{ emptyText: <Empty description="尚无模板" /> }}
                    renderItem={(t) => (
                      <List.Item>
                        <Card
                          size="small"
                          actions={[
                            <Button type="link" onClick={() => navigate('/analytics/notebooks')}>
                              用此模板新建
                            </Button>,
                            t.tenantId && (
                              <Button type="link" danger icon={<DeleteOutlined />}
                                      onClick={() => handleDelete(t.id, t.name)} />
                            ),
                          ].filter(Boolean)}
                        >
                          <Card.Meta
                            avatar={<div style={{ fontSize: 24 }}>{CATEGORY_ICON[t.category]}</div>}
                            title={<Space>{t.name}<Tag color={CATEGORY_COLOR[t.category]}>{t.category}</Tag></Space>}
                            description={t.description ?? '（无描述）'}
                          />
                          <div style={{ marginTop: 8, fontSize: 11, color: '#999' }}>
                            kernel: <code>{t.kernel}</code>
                            {!t.tenantId && <Tag color="gold" style={{ marginLeft: 8 }}>平台预置</Tag>}
                          </div>
                        </Card>
                      </List.Item>
                    )}
                  />
                </>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title="上传自定义算法模板"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={handleCreate}
        width={560}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模板名称" rules={[{ required: true }]}>
            <Input placeholder="如：RFM 客户分群" />
          </Form.Item>
          <Form.Item name="category" label="分类" rules={[{ required: true }]}>
            <Select options={[
              { value: 'CLUSTERING', label: '聚类' },
              { value: 'REGRESSION', label: '回归' },
              { value: 'FORECAST', label: '时序预测' },
              { value: 'CORRELATION', label: '相关性分析' },
              { value: 'EDA', label: '探索性分析' },
              { value: 'RFM', label: 'RFM 分群' },
            ]} />
          </Form.Item>
          <Form.Item name="storagePath" label="模板 .ipynb 在 MinIO 的路径" rules={[{ required: true }]}>
            <Input placeholder="templates/custom/my_rfm.ipynb" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="paramsSchemaJson" label="参数 schema（JSON）" tooltip="papermill 参数定义">
            <Input.TextArea rows={4} placeholder='[{"name":"table_fqn","type":"string","default":"iceberg.dwd.dwd_user"}]' />
          </Form.Item>
          <Form.Item name="kernel" label="Kernel" initialValue="python3">
            <Select options={[
              { value: 'python3', label: 'python3' },
              { value: 'pyspark', label: 'pyspark' },
            ]} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

const CATEGORY_ICON: Record<TemplateCategory, string> = {
  CLUSTERING: '🔵',
  REGRESSION: '🟢',
  FORECAST: '🟠',
  CORRELATION: '🟣',
  EDA: '🔵',
  RFM: '🔴',
};

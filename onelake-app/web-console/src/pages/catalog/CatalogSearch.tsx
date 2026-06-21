/**
 * 资产搜索浏览（对应原型 §8.6.1 升级版）。
 */
import { Input, Row, Col, Tag, List, Space, Avatar, Typography, Drawer, Form, Select, Checkbox, Button, message } from 'antd';
import { SearchOutlined, FireOutlined, AppstoreOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { searchHot } from '../../mock';
import { ClassificationBadge, PageHeader, SectionCard, StateView } from '../../components';
import type { Asset } from '../../types';
import { CatalogAPI } from '../../api';

const { Text } = Typography;

const LAYER_BG: Record<string, string> = {
  ODS: 'var(--ol-brand)',
  DWD: '#0369A1',
  DWS: '#B45309',
  ADS: 'var(--ol-success)',
};

export default function CatalogSearch() {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState('');
  const [layer, setLayer] = useState<string>();
  const [classification, setClassification] = useState<string>();
  const [owner, setOwner] = useState<string>();
  const [applyOpen, setApplyOpen] = useState<Asset | null>(null);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();

  const loadAssets = () => {
    setLoading(true);
    setLoadError(undefined);
    CatalogAPI.listAssets()
      .then(setAssets)
      .catch((e) => {
        const msg = e instanceof Error ? e.message : '目录资产加载失败';
        setLoadError(msg);
        message.error(msg);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAssets();
  }, []);

  const filtered = assets.filter((a) =>
    (!keyword || a.fqn.includes(keyword) || a.name.includes(keyword) || (a.description || '').includes(keyword)) &&
    (!layer || a.layer === layer) &&
    (!classification || a.classification === classification) &&
    (!owner || a.ownerName === owner)
  );

  return (
    <div className="ol-page">
      <PageHeader
        icon={<SearchOutlined />}
        title="搜索浏览"
        subtitle={<span className="ol-chip">数据目录 · L3-2</span>}
        description="按表名、字段、术语、负责人快速检索数据资产，支持分面筛选与申请访问"
      />

      {/* 全局搜索框 */}
      <SectionCard padded="md">
        <Input
          size="large"
          prefix={<SearchOutlined style={{ color: 'var(--ol-ink-3)' }} />}
          placeholder="搜表名 / 字段 / 术语…"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          style={{ borderRadius: 8 }}
        />
        <div style={{ marginTop: 10, display: 'flex', alignItems: 'center', gap: 6 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>热门：</Text>
          {searchHot.map((s) => (
            <Tag key={s} color="blue" style={{ cursor: 'pointer' }} onClick={() => setKeyword(s)}>{s}</Tag>
          ))}
        </div>
      </SectionCard>

      <Row gutter={16}>
        <Col xs={24} lg={5}>
          <SectionCard title="分面筛选" icon={<AppstoreOutlined />} padded="sm">
            <div style={{ marginBottom: 14 }}>
              <Text strong style={{ fontSize: 12 }}>层</Text>
              <div style={{ marginTop: 6 }}>
                <Checkbox.Group
                  options={['ODS', 'DWD', 'DWS', 'ADS'].map((v) => ({ label: v, value: v }))}
                  onChange={(v) => setLayer((v as any[])[0])}
                />
              </div>
            </div>
            <div style={{ marginBottom: 14 }}>
              <Text strong style={{ fontSize: 12 }}>密级</Text>
              <div style={{ marginTop: 6 }}>
                <Checkbox.Group
                  options={['L1', 'L2', 'L3', 'L4'].map((v) => ({ label: v, value: v }))}
                  onChange={(v) => setClassification((v as any[])[0])}
                />
              </div>
            </div>
            <div style={{ marginBottom: 14 }}>
              <Text strong style={{ fontSize: 12 }}>负责人</Text>
              <div style={{ marginTop: 6 }}>
                <Checkbox.Group
                  options={['张三', '李四', '王五'].map((v) => ({ label: v, value: v }))}
                  onChange={(v) => setOwner((v as any[])[0])}
                />
              </div>
            </div>
            <div>
              <Text strong style={{ fontSize: 12 }}>质量分</Text>
              <div style={{ marginTop: 6 }}>
                <Checkbox.Group options={['90+', '80+', '<80'].map((v) => ({ label: v, value: v }))} />
              </div>
            </div>
          </SectionCard>

          <SectionCard title="猜你需要" icon={<FireOutlined />} padded="sm" style={{ marginTop: 12 }}>
            <List
              size="small"
              dataSource={assets.slice(0, 3)}
              renderItem={(a) => (
                <List.Item style={{ padding: '6px 0' }}>
                  <Space size={8} style={{ minWidth: 0 }}>
                    <Avatar size="small" style={{ background: LAYER_BG[a.layer || 'ODS'], fontSize: 11 }}>{(a.layer || 'ODS')[0]}</Avatar>
                    <a className="ol-link ol-truncate" style={{ fontSize: 12 }} onClick={() => navigate(`/catalog/assets/${a.id}`)}>{a.fqn}</a>
                  </Space>
                </List.Item>
              )}
            />
          </SectionCard>
        </Col>

        <Col xs={24} lg={19}>
          <SectionCard title={`搜索结果 (${filtered.length})`} icon={<SearchOutlined />} flatBody>
            {loading ? (
              <StateView state="loading" rows={6} />
            ) : loadError ? (
              <StateView
                state="error"
                title="目录资产加载失败"
                description={loadError}
                onRetry={loadAssets}
              />
            ) : filtered.length === 0 ? (
              <StateView
                state="empty"
                title="没有找到匹配的资产"
                description="尝试调整关键字、分层、密级或负责人筛选"
              />
            ) : (
            <List
              dataSource={filtered}
              renderItem={(a) => (
                <List.Item
                  style={{ padding: '14px 16px', borderBottom: '1px solid var(--ol-line-soft)' }}
                  actions={[
                    <Button type="link" key="apply" onClick={() => setApplyOpen(a)}>申请访问</Button>,
                    <Button type="link" key="api" onClick={() => navigate(`/dataservice/apis/new?sourceFqn=${a.fqn}`)}>发布为 API</Button>,
                  ]}
                >
                  <List.Item.Meta
                    avatar={<Avatar style={{ background: LAYER_BG[a.layer || 'ODS'] }}>{(a.layer || 'ODS')[0]}</Avatar>}
                    title={
                      <Space size={8} wrap>
                        <a className="ol-link" style={{ fontSize: 14, fontWeight: 500 }} onClick={() => navigate(`/catalog/assets/${a.id}`)}>{a.fqn}</a>
                        <ClassificationBadge level={a.classification} />
                        <Tag color="blue">质量 {a.qualityScore}</Tag>
                        <Tag>订阅 {a.popularity}</Tag>
                      </Space>
                    }
                    description={
                      <Space split={<span className="ol-divider-v" />} wrap>
                        <Text type="secondary" style={{ fontSize: 12 }}>{a.description}</Text>
                        <Text type="secondary" style={{ fontSize: 12 }}>负责：{a.ownerName}</Text>
                      </Space>
                    }
                  />
                </List.Item>
              )}
            />
            )}
          </SectionCard>
        </Col>
      </Row>

      {/* 申请访问抽屉 */}
      <Drawer
        open={!!applyOpen}
        onClose={() => setApplyOpen(null)}
        title="申请访问"
        width={520}
        extra={
          <Space>
            <Button onClick={() => setApplyOpen(null)}>取消</Button>
            <Button type="primary" onClick={() => { setApplyOpen(null); message.success('已提交申请，可在「我的申请」跟踪'); }}>提交申请</Button>
          </Space>
        }
      >
        {applyOpen && (
          <Form layout="vertical" requiredMark="optional">
            <Form.Item label="资产">
              <Space>
                <Text strong>{applyOpen.fqn}</Text>
                <ClassificationBadge level={applyOpen.classification} />
              </Space>
            </Form.Item>
            <Form.Item label="字段范围">
              <Checkbox.Group
                options={['全部', ...applyOpen.columns.map((c) => c.name)].map((v) => ({ label: v, value: v }))}
                defaultValue={['全部']}
              />
            </Form.Item>
            {applyOpen.columns.some((c) => c.classification === 'L3' || c.classification === 'L4') && (
              <Tag color="warning" style={{ marginBottom: 16 }}>
                ⚠ 含敏感字段：{applyOpen.columns.filter((c) => c.classification === 'L3' || c.classification === 'L4').map((c) => c.name).join(', ')}
              </Tag>
            )}
            <Form.Item label="用途">
              <Select options={['报表分析', '风控模型', '产品功能', '其他'].map((v) => ({ label: v, value: v }))} />
            </Form.Item>
            <Form.Item label="使用周期">
              <Select options={['30 天', '90 天', '1 年'].map((v) => ({ label: v, value: v }))} defaultValue="90 天" />
            </Form.Item>
            <Form.Item label="权限">
              <Checkbox.Group
                options={['查样例', '查询', '下载', 'API'].map((v) => ({ label: v, value: v }))}
                defaultValue={['查样例', '查询']}
              />
            </Form.Item>
            <Form.Item label="审批链">
              <Text type="secondary" style={{ fontSize: 12 }}>资产负责人 → 安全合规</Text>
            </Form.Item>
          </Form>
        )}
      </Drawer>
    </div>
  );
}

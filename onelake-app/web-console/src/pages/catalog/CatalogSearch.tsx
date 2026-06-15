/**
 * 资产搜索浏览（对应原型 §8.6.1）。
 */
import { Card, Input, Row, Col, Tag, List, Space, Avatar, Typography, Drawer, Form, Select, Checkbox, Button, message } from 'antd';
import { SearchOutlined, StarOutlined, FireOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { lakehouseAssets, searchHot } from '../../mock';
import { ClassificationBadge } from '../../components';
import type { Asset } from '../../types';

const { Text } = Typography;

export default function CatalogSearch() {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState('');
  const [layer, setLayer] = useState<string>();
  const [classification, setClassification] = useState<string>();
  const [owner, setOwner] = useState<string>();
  const [applyOpen, setApplyOpen] = useState<Asset | null>(null);

  const filtered = lakehouseAssets.filter((a) =>
    (!keyword || a.fqn.includes(keyword) || a.name.includes(keyword) || (a.description || '').includes(keyword)) &&
    (!layer || a.layer === layer) &&
    (!classification || a.classification === classification) &&
    (!owner || a.ownerName === owner)
  );

  return (
    <>
      <Card>
        <Input size="large" prefix={<SearchOutlined />} placeholder="搜表名/字段/术语…" value={keyword} onChange={(e) => setKeyword(e.target.value)} />
        <div style={{ marginTop: 8 }}><Text type="secondary">热门：</Text>{searchHot.map((s) => <Tag key={s} color="blue" style={{ cursor: 'pointer' }} onClick={() => setKeyword(s)}>{s}</Tag>)}</div>
      </Card>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={5}>
          <Card title="分面筛选" size="small">
            <div style={{ marginBottom: 12 }}>
              <Text strong>层</Text>
              <div><Checkbox.Group options={['ODS', 'DWD', 'DWS', 'ADS'].map((v: any) => ({ label: v, value: v }))} onChange={(v) => setLayer((v as any[])[0])} /></div>
            </div>
            <div style={{ marginBottom: 12 }}>
              <Text strong>密级</Text>
              <div><Checkbox.Group options={['L1', 'L2', 'L3', 'L4'].map((v: any) => ({ label: v, value: v }))} onChange={(v) => setClassification((v as any[])[0])} /></div>
            </div>
            <div style={{ marginBottom: 12 }}>
              <Text strong>负责人</Text>
              <div><Checkbox.Group options={['张三', '李四', '王五'].map((v: any) => ({ label: v, value: v }))} onChange={(v) => setOwner((v as any[])[0])} /></div>
            </div>
            <div style={{ marginBottom: 12 }}>
              <Text strong>质量分</Text>
              <div><Checkbox.Group options={['90+', '80+', '<80'].map((v: any) => ({ label: v, value: v }))} /></div>
            </div>
          </Card>
          <Card title="猜你需要" size="small" style={{ marginTop: 12 }}>
            <List size="small" dataSource={lakehouseAssets.slice(0, 3)}
              renderItem={(a) => <List.Item><Avatar size="small" icon={<FireOutlined />} /><a onClick={() => navigate(`/catalog/assets/${a.id}`)}>{a.fqn}</a></List.Item>} />
          </Card>
        </Col>
        <Col span={19}>
          <Card title={`结果 (${filtered.length})`}>
            <List
              dataSource={filtered}
              renderItem={(a) => (
                <List.Item actions={[
                  <Button type="link" key="apply" onClick={() => setApplyOpen(a)}>申请访问</Button>,
                  <Button type="link" key="api" onClick={() => navigate(`/dataservice/apis/new?sourceFqn=${a.fqn}`)}>发布为 API</Button>,
                ]}>
                  <List.Item.Meta
                    avatar={<Avatar style={{ background: '#1677ff' }}>{a.layer[0]}</Avatar>}
                    title={<Space><a onClick={() => navigate(`/catalog/assets/${a.id}`)}>{a.fqn}</a><ClassificationBadge level={a.classification} /><Tag color="blue">质量 {a.qualityScore}</Tag><Tag>订阅 {a.popularity}</Tag></Space>}
                    description={<Space><Text type="secondary">{a.description}</Text><Text type="secondary">负责：{a.ownerName}</Text></Space>}
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>
      </Row>

      {/* 申请访问抽屉（§8.6.6） */}
      <Drawer open={!!applyOpen} onClose={() => setApplyOpen(null)} title="申请访问" width={520}
        extra={<Space><Button onClick={() => setApplyOpen(null)}>取消</Button><Button type="primary" onClick={() => { setApplyOpen(null); message.success('已提交申请，可在「我的申请」跟踪'); }}>提交申请</Button></Space>}>
        {applyOpen && (
          <Form layout="vertical">
            <Form.Item label="资产"><Text strong>{applyOpen.fqn}</Text> <ClassificationBadge level={applyOpen.classification} /></Form.Item>
            <Form.Item label="字段范围"><Checkbox.Group options={['全部', ...applyOpen.columns.map((c) => c.name)].map((v: any) => ({ label: v, value: v }))} defaultValue={['全部']} /></Form.Item>
            {applyOpen.columns.some((c) => c.classification === 'L3' || c.classification === 'L4') && (
              <Tag color="warning">⚠ 含敏感字段：{applyOpen.columns.filter((c) => c.classification === 'L3' || c.classification === 'L4').map((c) => c.name).join(', ')}</Tag>
            )}
            <Form.Item label="用途"><Select options={['报表分析', '风控模型', '产品功能', '其他'].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
            <Form.Item label="使用周期"><Select options={['30 天', '90 天', '1 年'].map((v: any) => ({ label: v, value: v }))} defaultValue="90 天" /></Form.Item>
            <Form.Item label="权限"><Checkbox.Group options={['查样例', '查询', '下载', 'API'].map((v: any) => ({ label: v, value: v }))} defaultValue={['查样例', '查询']} /></Form.Item>
            <Form.Item label="审批链"><Text>资产负责人 → 安全合规</Text></Form.Item>
          </Form>
        )}
      </Drawer>
    </>
  );
}

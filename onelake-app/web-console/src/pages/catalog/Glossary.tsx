/**
 * 业务术语表（对应原型 §8.6.4 升级版）。
 */
import { Row, Col, Tree, Typography, Tag, Space, Button } from 'antd';
import { BookOutlined, PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { glossaryTerms } from '../../mock';
import { Link } from 'react-router-dom';
import { PageHeader, SectionCard } from '../../components';

const { Text } = Typography;

export default function Glossary() {
  const [term, setTerm] = useState(glossaryTerms[0]);

  return (
    <div className="ol-page">
      <PageHeader
        icon={<BookOutlined />}
        title="业务术语表"
        subtitle={<span className="ol-chip">数据目录 · L3-2</span>}
        description="业务术语统一登记，含定义 / 口径 / 同义词 / 关联字段，支持业务 ↔ 物理映射"
        meta={[
          { label: '总术语', value: glossaryTerms.length },
          { label: '已审定', value: glossaryTerms.filter((t) => t.status === '已审定').length },
        ]}
        actions={<Button type="primary" icon={<PlusOutlined />}>新建术语</Button>}
      />

      <Row gutter={16}>
        <Col xs={24} lg={6}>
          <SectionCard title="分类树" icon={<BookOutlined />} padded="sm">
            <Tree
              defaultExpandAll
              treeData={[
                { title: <Text strong>交易域</Text>, key: 'TRADE', children: glossaryTerms.filter((t) => t.domain === '交易域').map((t) => ({ title: t.term, key: t.term })) },
                { title: <Text strong>用户域</Text>, key: 'USER', children: glossaryTerms.filter((t) => t.domain === '用户域').map((t) => ({ title: t.term, key: t.term })) },
              ]}
              onSelect={(keys) => { const t = glossaryTerms.find((g) => g.term === keys[0]); if (t) setTerm(t); }}
            />
          </SectionCard>
        </Col>
        <Col xs={24} lg={18}>
          <SectionCard
            title={
              <Space size={8}>
                <Text strong style={{ fontSize: 15 }}>{term.term}</Text>
                <Tag color="blue" style={{ margin: 0 }}>{term.domain}</Tag>
                <Tag color={term.status === '已审定' ? 'success' : 'default'} style={{ margin: 0 }}>{term.status}</Tag>
              </Space>
            }
            icon={<BookOutlined />}
          >
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>定义</Text>
                <div style={{ marginTop: 4, fontSize: 13, color: 'var(--ol-ink)' }}>{term.definition}</div>
              </div>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>口径</Text>
                <div style={{ marginTop: 4 }}>
                  <Text code style={{ fontSize: 12 }}>{term.caliber}</Text>
                </div>
              </div>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>同义词</Text>
                <div style={{ marginTop: 4, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                  {term.synonyms.split('、').map((s: string) => (
                    <span key={s} className="ol-chip">{s}</span>
                  ))}
                </div>
              </div>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>负责人</Text>
                <div style={{ marginTop: 4 }}>{term.owner}</div>
              </div>
              <div>
                <Text style={{ color: 'var(--ol-ink-3)', fontSize: 12 }}>关联物理字段</Text>
                <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
                  {term.related.map((r) => (
                    <Link key={r} to="/catalog/search" className="ol-link" style={{ fontSize: 12 }}>
                      <Text code style={{ fontSize: 12 }}>{r}</Text>
                    </Link>
                  ))}
                </div>
              </div>
            </Space>
          </SectionCard>
        </Col>
      </Row>
    </div>
  );
}

/**
 * 业务术语表（对应原型 §8.6.4）。
 */
import { Card, Row, Col, Tree, Typography, Tag, Space, Button, Descriptions } from 'antd';
import { useState } from 'react';
import { glossaryTerms } from '../../mock';
import { Link } from 'react-router-dom';

const { Text } = Typography;

export default function Glossary() {
  const [term, setTerm] = useState(glossaryTerms[0]);

  return (
    <Card title="数据目录 / 业务术语表" extra={<Button type="primary">+ 新建术语</Button>}>
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Tree defaultExpandAll treeData={[
              { title: '交易域', key: 'TRADE', children: glossaryTerms.filter((t) => t.domain === '交易域').map((t) => ({ title: t.term, key: t.term })) },
              { title: '用户域', key: 'USER', children: glossaryTerms.filter((t) => t.domain === '用户域').map((t) => ({ title: t.term, key: t.term })) },
            ]} onSelect={(keys) => { const t = glossaryTerms.find((g) => g.term === keys[0]); if (t) setTerm(t); }} />
          </Card>
        </Col>
        <Col span={18}>
          <Card size="small" title={<Space><Text strong>{term.term}</Text><Tag color="blue">{term.domain}</Tag><Tag color={term.status === '已审定' ? 'success' : 'default'}>{term.status}</Tag></Space>}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="定义">{term.definition}</Descriptions.Item>
              <Descriptions.Item label="口径"><Text code>{term.caliber}</Text></Descriptions.Item>
              <Descriptions.Item label="同义词">{term.synonyms}</Descriptions.Item>
              <Descriptions.Item label="负责人">{term.owner}</Descriptions.Item>
              <Descriptions.Item label="关联物理字段">
                <Space direction="vertical">
                  {term.related.map((r) => <Link key={r} to="/catalog/search">{r}</Link>)}
                </Space>
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>
    </Card>
  );
}

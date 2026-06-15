/**
 * 分层表浏览（对应原型 §8.3.1）。
 */
import { Card, Row, Col, Tree, Table, Tag, Space, Button, Input, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { lakehouseAssets } from '../../mock';
import { ClassificationBadge } from '../../components';
import type { Asset } from '../../types';

const { Text } = Typography;

export default function Tables() {
  const navigate = useNavigate();
  const [layer, setLayer] = useState<string>();
  const [domain, setDomain] = useState<string>();
  const [keyword, setKeyword] = useState('');

  const rows = lakehouseAssets.filter((a) =>
    (!layer || a.layer === layer) &&
    (!domain || a.domain === domain) &&
    (!keyword || a.fqn.includes(keyword) || a.name.includes(keyword))
  );

  return (
    <Row gutter={16}>
      <Col span={5}>
        <Card title="分层" size="small">
          <Tree
            defaultExpandAll
            treeData={[
              { title: '贴源 ODS', key: 'ODS', children: lakehouseAssets.filter((a) => a.layer === 'ODS').map((a) => ({ title: a.name, key: a.id, isLeaf: true })) },
              { title: '明细 DWD', key: 'DWD', children: lakehouseAssets.filter((a) => a.layer === 'DWD').map((a) => ({ title: <Space>{a.name}<ClassificationBadge level={a.classification} size="small" /></Space>, key: a.id, isLeaf: true })) },
              { title: '汇总 DWS', key: 'DWS', children: lakehouseAssets.filter((a) => a.layer === 'DWS').map((a) => ({ title: a.name, key: a.id, isLeaf: true })) },
              { title: '应用 ADS', key: 'ADS', children: lakehouseAssets.filter((a) => a.layer === 'ADS').map((a) => ({ title: a.name, key: a.id, isLeaf: true })) },
            ]}
            onSelect={(keys, info) => {
              const node = info.node;
              if (node && (node as any).isLeaf && keys[0]) navigate(`/lakehouse/tables/${keys[0]}`);
              else if (node) setLayer(node.key as string);
            }}
          />
          <div style={{ marginTop: 16 }}>
            <Text type="secondary">业务域</Text>
            <Tree treeData={[
              { title: '交易域', key: '交易' },
              { title: '用户域', key: '用户' },
              { title: '风控域', key: '风控' },
            ]} onSelect={(keys) => keys[0] && setDomain(keys[0] as string)} />
          </div>
        </Card>
      </Col>
      <Col span={19}>
        <Card title="湖仓与建模 / 分层浏览" extra={<Button type="primary" icon={<PlusOutlined />}>新建表（建模向导）</Button>}>
          <Space style={{ marginBottom: 16 }}>
            <Input.Search placeholder="搜表名/字段" allowClear onSearch={setKeyword} style={{ width: 240 }} />
          </Space>
          <Table rowKey="id" dataSource={rows} size="middle"
            columns={[
              { title: '表名', dataIndex: 'fqn', render: (v: string, r: Asset) => <a onClick={() => navigate(`/lakehouse/tables/${r.id}`)}>{v}</a> },
              { title: '层', dataIndex: 'layer', render: (l: string) => <Tag color="blue">{l}</Tag> },
              { title: '行数', dataIndex: 'rows', render: (v?: number) => v?.toLocaleString() || '-' },
              { title: '大小', dataIndex: 'sizeBytes', render: (v?: number) => v ? `${(v / 1e9).toFixed(2)} GB` : '-' },
              { title: '质量分', dataIndex: 'qualityScore', render: (v?: number) => v ? <Tag color={v > 90 ? 'success' : v > 80 ? 'warning' : 'error'}>{v}</Tag> : '-' },
              { title: '密级', dataIndex: 'classification', render: (c: string) => <ClassificationBadge level={c as any} /> },
              { title: '负责人', dataIndex: 'ownerName' },
            ]}
          />
        </Card>
      </Col>
    </Row>
  );
}

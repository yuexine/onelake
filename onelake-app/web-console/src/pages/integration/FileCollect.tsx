/**
 * 文件采集配置（对应原型 §8.2.6）。
 */
import { Card, Tabs, Table, Tag, Progress, Space, Switch, Form, Input, Select, Button, message } from 'antd';
import { fileWatchList } from '../../mock';

export default function FileCollect() {
  const tabs = [
    { key: 'list', label: '已监听文件', children: (
      <Table size="small" rowKey="id" dataSource={fileWatchList} pagination={false}
        columns={[
          { title: '文件名', dataIndex: 'filename' },
          { title: '大小 (MB)', dataIndex: 'sizeMb' },
          { title: 'MD5 校验', dataIndex: 'checksum', render: (c: string) => c === 'matched' ? <Tag color="success">✓ 一致</Tag> : c === 'mismatch' ? <Tag color="error">✗ 不符</Tag> : <Tag>未校验</Tag> },
          { title: '分片进度', dataIndex: 'progress', render: (p: number) => <Progress percent={p} size="small" style={{ width: 120 }} /> },
          { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === '已入湖' ? 'success' : s === '重传中' ? 'processing' : 'default'}>{s}</Tag> },
        ]} />
    ) },
    { key: 'config', label: '采集配置', children: (
      <Form layout="vertical" style={{ maxWidth: 600 }}>
        <Form.Item label="来源类型"><Select defaultValue="SFTP" options={['SFTP', 'FTP', 'NAS', 'S3'].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
        <Form.Item label="监听目录"><Input placeholder="/data/inbound/orders/" /></Form.Item>
        <Form.Item label="监听方式"><Select defaultValue="event" options={[{ label: '事件通知（S3→SQS）', value: 'event' }, { label: '定时轮询 5 分钟', value: 'poll' }].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
        <Form.Item label="文件匹配"><Input placeholder="orders_*.csv" /></Form.Item>
        <Form.Item label="稳定判定">大小连续 2 次不变才采集</Form.Item>
        <Space direction="vertical">
          <Space><Switch defaultChecked /> 大文件分片上传（64MB/片）+ 断点续传</Space>
          <Space><Switch defaultChecked /> MD5/SHA-256 校验，不一致自动重传</Space>
          <Space><Switch defaultChecked /> 按内容哈希去重，命中已采则跳过</Space>
        </Space>
        <div style={{ marginTop: 16 }}><Button type="primary" onClick={() => message.success('已保存')}>保存配置</Button></div>
      </Form>
    ) },
  ];

  return <Card title="数据集成 / 文件采集"><Tabs items={tabs} /></Card>;
}

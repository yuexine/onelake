/**
 * API 构建向导（5 步，对应原型 §8.8.2）。
 */
import { Card, Steps, Form, Select, Input, Table, Switch, InputNumber, Button, Space, Typography, Tag, message } from 'antd';
import { ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { lakehouseAssets } from '../../mock';
import { ClassificationBadge } from '../../components';

const { Text } = Typography;

export default function ApiWizard() {
  const navigate = useNavigate();
  const [sp] = useSearchParams();
  const [step, setStep] = useState(0);
  const sourceFqn = sp.get('sourceFqn');
  const [params, setParams] = useState<any[]>([
    { name: 'order_id', type: 'BIGINT', required: true },
    { name: 'dt', type: 'DATE', required: false },
  ]);
  const [returns, setReturns] = useState<any[]>([
    { name: 'order_id', type: 'BIGINT' },
    { name: 'phone', type: 'STRING', classification: 'L3', masked: true },
    { name: 'amount', type: 'DECIMAL' },
  ]);

  const steps = ['选数据源', '参数与响应', '缓存与性能', '鉴权与限流', '预览与发布'];

  return (
    <Card title={<Space><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/dataservice/apis')} />API 构建向导</Space>}>
      <Steps current={step} items={steps.map((s) => ({ title: s }))} style={{ marginBottom: 24 }} />

      {step === 0 && (
        <Form layout="vertical">
          <Form.Item label="来源（可从资产详情/SQL 工作台带参进入）" required>
            <Select defaultValue={sourceFqn || 'a-1'} options={lakehouseAssets.map((a) => ({ label: `${a.fqn} (${a.description || ''})`, value: a.id }))} />
          </Form.Item>
          <Form.Item label="来源方式">
            <Select options={[{ label: '选表/模型', value: 'table' }, { label: '粘贴 SQL', value: 'sql' }].map((v: any) => ({ label: v, value: v }))} defaultValue="table" />
          </Form.Item>
          <Form.Item label="SQL"><Input.TextArea defaultValue={'SELECT order_id, phone, amount FROM ads.ads_sales_df WHERE order_id = :order_id'} rows={4} /></Form.Item>
        </Form>
      )}

      {step === 1 && (
        <>
          <Card size="small" title="请求参数" style={{ marginBottom: 12 }}>
            <Table size="small" rowKey="name" dataSource={params} pagination={false}
              columns={[
                { title: '参数名', dataIndex: 'name' },
                { title: '类型', dataIndex: 'type' },
                { title: '必填', dataIndex: 'required', render: (r: boolean) => <Tag color={r ? 'red' : 'default'}>{r ? '必填' : '选填'}</Tag> },
                { title: '默认值', render: () => <Input size="small" style={{ width: 120 }} /> },
                { title: '校验规则', render: () => <Input size="small" placeholder="正则/范围" style={{ width: 160 }} /> },
              ]} />
          </Card>
          <Card size="small" title="返回字段">
            <Table size="small" rowKey="name" dataSource={returns} pagination={false}
              columns={[
                { title: '字段', dataIndex: 'name' },
                { title: '类型', dataIndex: 'type' },
                { title: '密级', dataIndex: 'classification', render: (c: string) => c ? <ClassificationBadge level={c as any} /> : null },
                { title: '动态脱敏', dataIndex: 'masked', render: (m?: boolean) => <Switch size="small" defaultChecked={m} /> },
              ]} />
          </Card>
        </>
      )}

      {step === 2 && (
        <Form layout="vertical">
          <Form.Item label="缓存 TTL"><InputNumber defaultValue={60} /> 秒</Form.Item>
          <Form.Item label="分页策略"><Select options={[{ label: 'limit/offset', value: 'offset' }, { label: '游标（推荐大结果集）', value: 'cursor' }].map((v: any) => ({ label: v, value: v }))} defaultValue="cursor" /></Form.Item>
          <Form.Item label="排序字段"><Input placeholder="stat_date DESC" /></Form.Item>
          <Form.Item label="超时（ms）"><InputNumber defaultValue={5000} /></Form.Item>
        </Form>
      )}

      {step === 3 && (
        <Form layout="vertical">
          <Form.Item label="鉴权方式"><Select mode="multiple" options={['AppKey+Secret', 'OAuth2', 'JWT'].map((v: any) => ({ label: v, value: v }))} defaultValue={['AppKey+Secret']} /></Form.Item>
          <Form.Item label="时间戳防重放"><Switch defaultChecked /></Form.Item>
          <Form.Item label="QPS 限制"><InputNumber defaultValue={50} /></Form.Item>
          <Form.Item label="并发限制"><InputNumber defaultValue={20} /></Form.Item>
          <Form.Item label="熔断阈值"><InputNumber defaultValue={50} /> % 错误率触发降级</Form.Item>
          <Form.Item label="IP 白名单"><Select mode="tags" placeholder="10.0.0.0/24" /></Form.Item>
          <Card size="small" title="行/列级权限（越权防护）">
            <div><Tag color="blue">行级</Tag> 注入 tenant_id = :ctx.tenant（防水平越权）</div>
            <div><Tag color="blue">列级</Tag> phone 仅 Sec 角色可见明文，其余动态脱敏</div>
          </Card>
        </Form>
      )}

      {step === 4 && (
        <>
          <Card size="small" title="在线调试器">
            <Space><Text>order_id=</Text><Input defaultValue="1001" style={{ width: 120 }} /><Text>dt=</Text><Input placeholder="2026-06-14" style={{ width: 140 }} /><Button type="primary" onClick={() => message.success('200 OK')}>发送</Button></Space>
            <pre style={{ marginTop: 12, background: '#f5f5f5', padding: 12 }}>响应 200: &#123;"order_id":1001,"phone":"138****8888","amount":99.0&#125;
（按调用方角色动态脱敏）</pre>
          </Card>
          <Card size="small" title="OpenAPI 文档" style={{ marginTop: 12 }}><Button>生成 OpenAPI</Button></Card>
        </>
      )}

      <div style={{ marginTop: 24, textAlign: 'right' }}>
        <Space>
          <Button onClick={() => navigate('/dataservice/apis')}>取消</Button>
          <Button>保存草稿</Button>
          {step > 0 && <Button onClick={() => setStep(step - 1)}><ArrowLeftOutlined /> 上一步</Button>}
          {step < 4 && <Button type="primary" onClick={() => setStep(step + 1)}>下一步 <ArrowRightOutlined /></Button>}
          {step === 4 && <Button type="primary" icon={<CheckOutlined />} onClick={() => { message.success('API 已发布'); navigate('/dataservice/apis'); }}>发布</Button>}
        </Space>
      </div>
    </Card>
  );
}

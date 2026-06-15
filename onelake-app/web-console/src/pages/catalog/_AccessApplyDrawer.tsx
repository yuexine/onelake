/**
 * 访问申请抽屉（§8.6.6 / §8.6.7 状态机）。
 */
import { Drawer, Form, Select, Checkbox, Button, Space, Tag, Typography, message } from 'antd';
import type { Asset } from '../../types';
import { ClassificationBadge } from '../../components';

const { Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  asset: Asset | null;
}

export function AccessApplyDrawer({ open, onClose, asset }: Props) {
  return (
    <Drawer open={open} onClose={onClose} title="申请访问" width={520}
      extra={<Space><Button onClick={onClose}>取消</Button>
        <Button type="primary" onClick={() => { onClose(); message.success('已提交申请，可在「我的申请」跟踪'); }}>提交申请</Button></Space>}>
      {asset && (
        <Form layout="vertical">
          <Form.Item label="资产"><Text strong>{asset.fqn}</Text> <ClassificationBadge level={asset.classification} /></Form.Item>
          <Form.Item label="字段范围">
            <Checkbox.Group options={['全部', ...asset.columns.map((c) => c.name)].map((v: any) => ({ label: v, value: v }))} defaultValue={['全部']} />
          </Form.Item>
          {asset.columns.some((c) => c.classification === 'L3' || c.classification === 'L4') && (
            <Tag color="warning">⚠ 含敏感字段：{asset.columns.filter((c) => c.classification === 'L3' || c.classification === 'L4').map((c) => c.name).join(', ')}</Tag>
          )}
          <Form.Item label="用途"><Select options={['报表分析', '风控模型', '产品功能', '其他'].map((v: any) => ({ label: v, value: v }))} /></Form.Item>
          <Form.Item label="使用周期"><Select options={['30 天', '90 天', '1 年'].map((v: any) => ({ label: v, value: v }))} defaultValue="90 天" /></Form.Item>
          <Form.Item label="权限"><Checkbox.Group options={['查样例', '查询', '下载', 'API'].map((v: any) => ({ label: v, value: v }))} defaultValue={['查样例', '查询']} /></Form.Item>
          <Form.Item label="审批链"><Text>资产负责人 → 安全合规</Text></Form.Item>
        </Form>
      )}
    </Drawer>
  );
}

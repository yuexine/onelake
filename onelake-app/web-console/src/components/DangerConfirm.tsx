/**
 * 危险操作确认弹窗（§2.4 危险操作 + §2.6 批量操作）。
 * 删除/下线/破坏性变更需输入名称确认 + 展示影响分析。
 */
import { Modal, Input, Alert, Descriptions, Typography } from 'antd';
import { ExclamationCircleOutlined } from '@ant-design/icons';
import { useState } from 'react';

const { Text } = Typography;

interface ImpactItem {
  label: string;
  value: number | string;
}

interface Props {
  open: boolean;
  title: string;
  description?: string;
  confirmName?: string;            // 需要输入的确认词（通常是表名/对象名）
  impacts?: ImpactItem[];
  impactLevel?: 'LOW' | 'MEDIUM' | 'HIGH';
  cancelText?: string;
  okText?: string;
  okType?: 'danger' | 'primary';
  onCancel: () => void;
  onConfirm: () => void;
}

export function DangerConfirm({
  open, title, description, confirmName, impacts = [],
  impactLevel = 'MEDIUM', cancelText = '取消', okText = '确认',
  okType = 'danger', onCancel, onConfirm,
}: Props) {
  const [input, setInput] = useState('');
  const confirmed = !confirmName || input === confirmName;
  const levelColor = impactLevel === 'HIGH' ? '#ff4d4f' : impactLevel === 'MEDIUM' ? '#fa8c16' : '#52c41a';

  return (
    <Modal
      open={open}
      title={<><ExclamationCircleOutlined style={{ color: levelColor, marginRight: 8 }} />{title}</>}
      onCancel={() => { setInput(''); onCancel(); }}
      footer={[
        <button key="cancel" className="ant-btn" onClick={() => { setInput(''); onCancel(); }}>{cancelText}</button>,
        <button
          key="ok"
          className={`ant-btn ${okType === 'danger' ? 'ant-btn-dangerous ant-btn-primary' : 'ant-btn-primary'}`}
          disabled={!confirmed}
          onClick={() => { setInput(''); onConfirm(); }}
        >{okText}</button>,
      ]}
    >
      {description && <Alert type="warning" message={description} style={{ marginBottom: 16 }} showIcon />}
      {impacts.length > 0 && (
        <Descriptions title="影响分析" size="small" column={2} bordered style={{ marginBottom: 16 }}>
          {impacts.map((i) => (
            <Descriptions.Item key={i.label} label={i.label}>
              <Text strong style={{ color: typeof i.value === 'number' && i.value > 0 ? levelColor : undefined }}>{i.value}</Text>
            </Descriptions.Item>
          ))}
        </Descriptions>
      )}
      {confirmName && (
        <div>
          <Text type="secondary">请输入 <Text strong code>{confirmName}</Text> 以确认：</Text>
          <Input value={input} onChange={(e) => setInput(e.target.value)} style={{ marginTop: 8 }} placeholder={confirmName} />
        </div>
      )}
    </Modal>
  );
}

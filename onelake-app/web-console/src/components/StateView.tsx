/**
 * 空状态/加载/错误/无权限四态通用组件（§2.3 + §2.5）。
 */
import { Empty, Skeleton, Result, Button } from 'antd';
import { LockOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';

interface Props {
  state: 'empty' | 'loading' | 'error' | 'no-permission';
  title?: string;
  description?: string;
  cta?: ReactNode;
  onRetry?: () => void;
  onApply?: () => void;
  rows?: number;
}

export function StateView({ state, title, description, cta, onRetry, onApply, rows = 5 }: Props) {
  switch (state) {
    case 'empty':
      return (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={description || '暂无数据'}
          style={{ padding: 40 }}
        >
          {cta}
        </Empty>
      );
    case 'loading':
      return <Skeleton active paragraph={{ rows }} />;
    case 'error':
      return (
        <Result
          status="warning"
          title={title || '加载失败'}
          subTitle={description || '请稍后重试，或联系系统管理员'}
          extra={
            <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>
              重试
            </Button>
          }
        />
      );
    case 'no-permission':
      return (
        <Result
          icon={<LockOutlined style={{ color: '#faad14' }} />}
          status="info"
          title={title || '无访问权限'}
          subTitle={description || '该资源受密级保护，需要负责人或安全合规授权才能查看'}
          extra={
            onApply && (
              <Button type="primary" onClick={onApply}>
                申请访问
              </Button>
            )
          }
        />
      );
  }
}

/**
 * 空/载/错/无权限 四态组件（§2.3 + §2.5）
 *   - empty: 友好插画位 + CTA
 *   - loading: 骨架屏
 *   - error: 异常 + 重试
 *   - no-permission: 申请访问入口
 */
import { Empty, Skeleton, Result, Button } from 'antd';
import { LockOutlined, ReloadOutlined, InboxOutlined, FrownOutlined } from '@ant-design/icons';
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
        <div className="ol-anim-fade" style={{ padding: 48, textAlign: 'center' }}>
          <div
            style={{
              width: 64, height: 64, borderRadius: 16, margin: '0 auto 16px',
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              background: 'var(--ol-fill-soft)', color: 'var(--ol-ink-4)', fontSize: 28,
            }}
          >
            <InboxOutlined />
          </div>
          <div style={{ fontSize: 14, color: 'var(--ol-ink)', fontWeight: 500, marginBottom: 4 }}>
            {title || '暂无数据'}
          </div>
          <div style={{ fontSize: 13, color: 'var(--ol-ink-3)', marginBottom: 16 }}>
            {description || '此处数据尚未生成，请稍后再试或检查筛选条件'}
          </div>
          {cta}
        </div>
      );
    case 'loading':
      return (
        <div style={{ padding: 20 }}>
          <Skeleton active paragraph={{ rows }} />
        </div>
      );
    case 'error':
      return (
        <div className="ol-anim-fade" style={{ padding: 40 }}>
          <Result
            icon={<FrownOutlined style={{ color: 'var(--ol-error)' }} />}
            status="warning"
            title={<span style={{ fontSize: 16 }}>{title || '加载失败'}</span>}
            subTitle={<span style={{ fontSize: 13, color: 'var(--ol-ink-3)' }}>{description || '请稍后重试，或联系系统管理员'}</span>}
            extra={
              <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>
            }
          />
        </div>
      );
    case 'no-permission':
      return (
        <div className="ol-anim-fade" style={{ padding: 40 }}>
          <Result
            icon={<LockOutlined style={{ color: 'var(--ol-warning)' }} />}
            status="info"
            title={<span style={{ fontSize: 16 }}>{title || '无访问权限'}</span>}
            subTitle={<span style={{ fontSize: 13, color: 'var(--ol-ink-3)' }}>{description || '该资源受密级保护，需要负责人或安全合规授权才能查看'}</span>}
            extra={onApply && <Button type="primary" onClick={onApply}>申请访问</Button>}
          />
        </div>
      );
  }
}

export { Empty };

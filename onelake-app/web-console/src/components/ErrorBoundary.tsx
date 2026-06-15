/**
 * 全局错误边界
 *   - 顶层包裹 Routes，捕获任意子树渲染异常
 *   - 显示友好的错误占位 + 重试按钮
 *   - 生产环境上报到监控（TODO）
 */
import { Component, type ReactNode } from 'react';
import { Result, Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';

interface Props {
  children: ReactNode;
  fallback?: (error: Error, retry: () => void) => ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: { componentStack: string }) {
    // 上报 TODO
    console.error('[ErrorBoundary]', error, info.componentStack);
  }

  retry = () => this.setState({ error: null });

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    if (this.props.fallback) return this.props.fallback(error, this.retry);

    return (
      <div style={{ padding: 40 }}>
        <Result
          status="500"
          title={<span style={{ fontSize: 16 }}>页面渲染出错</span>}
          subTitle={
            <span style={{ fontSize: 13, color: 'var(--ol-ink-3)', wordBreak: 'break-all' }}>
              {error.message || '未知错误'}
            </span>
          }
          extra={
            <Button type="primary" icon={<ReloadOutlined />} onClick={this.retry}>
              重试
            </Button>
          }
        />
      </div>
    );
  }
}

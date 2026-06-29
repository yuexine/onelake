/**
 * 公开分享大屏渲染页（无鉴权通道 /share/screen/:token）。
 *
 * - 不挂在 <App /> layout 下，避免 Sider / Header 干扰
 * - 仅渲染只读快照 + 后续根据 widget.data 走 guest 数据通道
 *
 * P1 简化：仅展示快照 JSON；P2/P3 接入组件渲染 + 公开数据查询。
 */
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Alert, Spin } from 'antd';
import { AnalyticsAPI } from '../../api';

export default function ScreenShare() {
  const { token } = useParams<{ token: string }>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<{ canvas: any; spec: any } | null>(null);
  const [version, setVersion] = useState<number | null>(null);

  useEffect(() => {
    if (!token) return;
    AnalyticsAPI.shareSnapshot(token)
      .then((resp) => {
        setSnapshot(resp.snapshot as any);
        setVersion(resp.version);
      })
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, [token]);

  if (loading) return <div style={{ padding: 48, textAlign: 'center' }}><Spin tip="加载大屏..." /></div>;
  if (error) return (
    <div style={{ padding: 48 }}>
      <Alert type="error" showIcon message="分享链接无效" description={error} />
    </div>
  );

  return (
    <div style={{
      width: '100vw', height: '100vh',
      background: snapshot?.canvas?.background ?? '#0a1a2f',
      overflow: 'auto',
    }}>
      <div style={{ padding: 24, color: '#fff' }}>
        <h2 style={{ color: '#fff' }}>分享大屏 v{version}</h2>
        <p style={{ color: '#aaa' }}>
          公开分享通道仅渲染已聚合 / 无行级过滤的快照（见 §5.3 安全治理）。
          P2/P3 阶段将接入完整组件渲染。
        </p>
        <pre style={{ color: '#0f0', background: 'rgba(0,0,0,0.4)', padding: 12, maxHeight: 400, overflow: 'auto' }}>
          {JSON.stringify(snapshot, null, 2)}
        </pre>
      </div>
    </div>
  );
}

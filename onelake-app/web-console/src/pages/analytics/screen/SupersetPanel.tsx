/**
 * Superset 嵌入面板（对应设计方案 §7.4）。
 *
 * 关键：
 * 1. guest token 由后端 SupersetEmbedService 用服务账号签发（带租户 RLS），
 *    前端绝不直接调 Superset API。
 * 2. SDK 调 embedDashboard 一次性渲染（mountPoint 不变则不重渲染）。
 */
import { useEffect, useRef } from 'react';
import { embedDashboard } from '@superset-ui/embedded-sdk';
import { AnalyticsAPI } from '../../../api';
import { Spin } from 'antd';

export interface SupersetPanelProps {
  uuid: string;
  supersetDomain?: string;
}

export function SupersetPanel({ uuid, supersetDomain }: SupersetPanelProps) {
  const ref = useRef<HTMLDivElement>(null);
  const mountedRef = useRef<string | null>(null);

  useEffect(() => {
    if (!ref.current || mountedRef.current === uuid) return;
    mountedRef.current = uuid;
    const domain = supersetDomain ?? import.meta.env.VITE_SUPERSET_DOMAIN ?? 'http://localhost:8088';
    embedDashboard({
      id: uuid,
      supersetDomain: domain,
      mountPoint: ref.current,
      fetchGuestToken: async () => {
        const { token } = await AnalyticsAPI.supersetGuestToken(uuid);
        return token;
      },
      dashboardUiConfig: { hideTitle: true, filters: { expanded: false } },
    }).catch((err) => {
      // 嵌入失败时降级显示提示
      console.error('superset embed failed', err);
      if (ref.current) {
        ref.current.innerHTML = '<div style="padding:24px;color:#faad14">Superset 面板嵌入失败，请检查数据源是否含 tenant_id 列</div>';
      }
    });
  }, [uuid, supersetDomain]);

  return (
    <div ref={ref} style={{ width: '100%', height: '100%', minHeight: 240 }}>
      <Spin tip="加载 Superset 面板..." style={{ paddingTop: 40 }} />
    </div>
  );
}

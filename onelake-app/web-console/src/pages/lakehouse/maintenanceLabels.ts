import type { AssetMaintenanceOperation } from '../../types';

export const maintenanceOperationLabels: Record<AssetMaintenanceOperation, string> = {
  OPTIMIZE: 'Compaction',
  EXPIRE_SNAPSHOTS: '清理快照',
  REMOVE_ORPHAN_FILES: '清理孤儿文件',
};

const maintenanceStatusLabels: Record<string, string> = {
  OK: '维护正常',
  WARN: '待维护',
  CRITICAL: '严重风险',
  UNKNOWN: '未评估',
};

const maintenanceFreshnessLabels: Record<string, string> = {
  OK: '新鲜度正常',
  BREACHED: '超出 SLA',
  UNKNOWN: '新鲜度未知',
};

const maintenanceRiskLabels: Record<string, string> = {
  FRESHNESS_UNKNOWN: '新鲜度未知',
  FRESHNESS_SLA_BREACHED: '新鲜度超 SLA',
  SMALL_FILE_RISK: '小文件风险',
  ICEBERG_METADATA_UNAVAILABLE: '元数据不可用',
};

export function maintenanceStatusColor(status?: string) {
  if (status === 'OK') return 'success';
  if (status === 'CRITICAL') return 'error';
  if (status === 'WARN') return 'warning';
  return 'default';
}

export function maintenanceFreshnessColor(status?: string) {
  if (status === 'OK') return 'success';
  if (status === 'BREACHED') return 'error';
  return 'default';
}

export function maintenanceStatusLabel(status?: string) {
  return maintenanceStatusLabels[status || 'UNKNOWN'] || status || '未评估';
}

export function maintenanceFreshnessLabel(status?: string) {
  return maintenanceFreshnessLabels[status || 'UNKNOWN'] || status || '新鲜度未知';
}

export function maintenanceRiskLabel(risk: string) {
  return maintenanceRiskLabels[risk] || risk;
}

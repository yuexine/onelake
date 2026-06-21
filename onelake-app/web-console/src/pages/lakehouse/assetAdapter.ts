import type { Asset } from '../../types';

const LAYERS = ['ODS', 'DWD', 'DWS', 'ADS'] as const;

function shortName(fqn?: string) {
  if (!fqn) return '-';
  const dot = fqn.lastIndexOf('.');
  return dot >= 0 ? fqn.slice(dot + 1) : fqn;
}

function layerFromFqn(fqn?: string): Asset['layer'] {
  const prefix = (fqn?.split('.')[0] || '').toUpperCase();
  const match = LAYERS.find((layer) => prefix.startsWith(layer));
  return match || 'ODS';
}

export function normalizeCatalogAsset(asset: Asset): Asset {
  const fqn = asset.fqn || '';
  return {
    ...asset,
    id: asset.id,
    fqn,
    name: asset.name || shortName(fqn),
    type: asset.type || 'TABLE',
    layer: asset.layer || layerFromFqn(fqn),
    domain: asset.domain || '未归属',
    ownerName: asset.ownerName || '-',
    description: asset.description || '由 Catalog 资产目录同步',
    tags: Array.isArray(asset.tags) ? asset.tags : [],
    popularity: asset.popularity ?? 0,
    accessCount: asset.accessCount ?? 0,
    columns: Array.isArray(asset.columns) ? asset.columns : [],
    partitions: Array.isArray(asset.partitions) ? asset.partitions : [],
    format: asset.format || 'ICEBERG',
  };
}

export function normalizeCatalogAssets(assets: Asset[]) {
  return assets.map(normalizeCatalogAsset);
}

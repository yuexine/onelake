/**
 * QUALITY_GATE inspector — embeds the 4-class QualityGateCards.
 *
 * <p>Config schema (jsonb on pipeline_task): { gates: QualityGate[] }
 * On save, gates are attached to the Spark-produced target table.
 */
import { useEffect, useState } from 'react';
import { Alert, Form, Input } from 'antd';
import type { InspectorProps } from '../InspectorRouter';
import {
  QualityGateCards,
  defaultQualityGates,
  type QualityGate,
} from './QualityGateCards';

interface GateConfig {
  gates: QualityGate[];
  targetModelFqn?: string;
}

function parseConfig(raw: Record<string, unknown> | undefined): GateConfig {
  const gates = Array.isArray(raw?.gates) ? (raw!.gates as QualityGate[]) : defaultQualityGates();
  return {
    gates,
    targetModelFqn: typeof raw?.targetModelFqn === 'string' ? raw.targetModelFqn : undefined,
  };
}

function configToRecord(cfg: GateConfig): Record<string, unknown> {
  return { gates: cfg.gates, targetModelFqn: cfg.targetModelFqn };
}

export function QualityGateInspector({ task, onChange }: InspectorProps) {
  const [cfg, setCfg] = useState<GateConfig>(() => parseConfig(task.config));

  // Re-sync when task changes (different selection)
  useEffect(() => {
    setCfg(parseConfig(task.config));
  }, [task.id, task.config]);

  const updateGates = (gates: QualityGate[]) => {
    const next: GateConfig = { ...cfg, gates };
    setCfg(next);
    onChange({ taskType: 'QUALITY_GATE', config: configToRecord(next) });
  };

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Alert
        type="info"
        showIcon
        message="质量门禁"
        description="配置 4 类校验规则。运行时绑定 Spark 产出表，校验不通过可阻断流水线发布。"
      />
      <Form layout="vertical" size="small">
        <Form.Item
          label="目标模型 FQN"
          tooltip="质量门禁会绑定到这个 Spark 产出表"
        >
          <Input
            value={cfg.targetModelFqn ?? task.targetFqn ?? ''}
            placeholder="iceberg.dwd.orders"
            onChange={(e) => {
              const next = { ...cfg, targetModelFqn: e.target.value };
              setCfg(next);
              onChange({
                taskType: 'QUALITY_GATE',
                targetFqn: e.target.value,
                config: configToRecord(next),
              });
            }}
          />
        </Form.Item>
      </Form>

      <QualityGateCards
        value={cfg.gates}
        onChange={updateGates}
        targetColumns={extractColumnsFromConfig(task.config)}
      />

    </div>
  );
}

function extractColumnsFromConfig(raw: Record<string, unknown> | undefined): string[] | undefined {
  const cols = raw?.columns;
  if (Array.isArray(cols)) {
    return cols.filter((c): c is string => typeof c === 'string');
  }
  return undefined;
}

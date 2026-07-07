import type { Dag, DagNode, PipelineTask } from '../types';

const catalogPrefixes = new Set(['onelake', 'iceberg', 'hive']);

function parseOperatorGraph(value: unknown): { nodes?: DagNode[] } | undefined {
  if (!value) return undefined;
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as { nodes?: DagNode[] };
    } catch {
      return undefined;
    }
  }
  if (typeof value === 'object') return value as { nodes?: DagNode[] };
  return undefined;
}

function dagDefinition(dag: Dag): Record<string, unknown> {
  return Array.isArray(dag.definition) ? {} : dag.definition || {};
}

export function dagNodes(dag: Dag): DagNode[] {
  if (Array.isArray(dag.definition)) return dag.definition;
  const definition = dagDefinition(dag);
  const directNodes = Array.isArray(definition.nodes) ? definition.nodes as DagNode[] : [];
  const graph = parseOperatorGraph(definition.operatorGraph);
  const graphNodes = Array.isArray(graph?.nodes) ? graph.nodes : [];
  return [...directNodes, ...graphNodes];
}

export function normalizePipelineFqn(value: string) {
  const parts = value.trim().toLowerCase().split('.').filter(Boolean);
  if (parts.length >= 3 && catalogPrefixes.has(parts[0])) {
    return parts.slice(1).join('.');
  }
  return parts.join('.');
}

export function fqnMatches(candidate: unknown, targetFqn: string) {
  if (typeof candidate !== 'string' || !candidate || !targetFqn) return false;
  return normalizePipelineFqn(candidate) === normalizePipelineFqn(targetFqn);
}

function stringList(value: unknown) {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string');
  return typeof value === 'string' ? [value] : [];
}

function nodeTargetFqnCandidates(node: DagNode) {
  const record = node as DagNode & { targetFqn?: unknown };
  return [
    record.targetFqn,
    node.config?.targetFqn,
    node.config?.targetModelFqn,
    node.params?.targetFqn,
    node.params?.targetModelFqn,
  ];
}

function configTargetFqnCandidates(config?: Record<string, unknown>) {
  if (!config) return [];
  return [
    config.targetFqn,
    config.targetModelFqn,
    ...stringList(config.targetFqns),
  ];
}

export function dagContainsTargetFqn(dag: Dag, targetFqn: string) {
  if (!targetFqn) return true;
  const definition = dagDefinition(dag);
  const definitionTargets = [
    definition.targetFqn,
    ...stringList(definition.targetFqns),
  ];
  return definitionTargets.some((candidate) => fqnMatches(candidate, targetFqn))
    || dagNodes(dag).some((node) => nodeTargetFqnCandidates(node).some((candidate) => fqnMatches(candidate, targetFqn)));
}

export function pipelineTaskContainsTargetFqn(task: PipelineTask, targetFqn: string) {
  return [
    task.targetFqn,
    ...configTargetFqnCandidates(task.config),
  ].some((candidate) => fqnMatches(candidate, targetFqn));
}

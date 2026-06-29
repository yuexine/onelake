package com.onelake.orchestration.domain.enums;

/**
 * Edge layer for pipeline_task_edge.
 *
 * <p>See C2 in docs/流水线模块重设计方案.md §6.3.1 (edge contract semantics).
 * <ul>
 *   <li>{@code PIPELINE} — task-level ordering and data-flow wiring within a Spark pipeline.</li>
 *   <li>{@code CROSS_ENGINE} — reserved for future external runtime boundaries.</li>
 * </ul>
 */
public enum EdgeLayer {
    PIPELINE,
    CROSS_ENGINE
}

package com.onelake.orchestration.domain.enums;

/**
 * Pipeline kind for orchestration.dag.pipeline_kind.
 * See docs/流水线模块重设计方案.md §6.1.
 */
public enum PipelineKind {
    /** Empty canvas; user assembles tasks freely. */
    BLANK,
    /** ODS→DWD governance template (prepopulated tasks). */
    ODS_DWD,
    /** Multi-layer processing template (ODS→DWD→DWS→ADS). */
    MULTI_LAYER
}

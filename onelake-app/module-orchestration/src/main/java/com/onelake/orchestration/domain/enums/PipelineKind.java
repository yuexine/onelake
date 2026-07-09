package com.onelake.orchestration.domain.enums;

/**
 * {@code orchestration.dag.pipeline_kind} 的流水线类型。
 */
public enum PipelineKind {
    /** 空白画布，用户自由组装节点。 */
    BLANK,
    /** ODS→DWD 治理模板，预置标准节点。 */
    ODS_DWD,
    /** 多层加工模板，覆盖 ODS→DWD→DWS→ADS。 */
    MULTI_LAYER
}

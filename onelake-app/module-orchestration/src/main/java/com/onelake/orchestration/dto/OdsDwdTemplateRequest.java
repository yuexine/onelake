package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request body for the ODS→DWD template pipeline (P3).
 *
 * <p>Prepopulates a BLANK pipeline with the Spark-only canonical structure:
 * <ul>
 *   <li>{@code SYNC_REF} pointing at {@code sourceFqn}</li>
 *   <li>optional {@code SPARK_SQL} field-governance node when requested</li>
 *   <li>{@code SPARK_SQL} DWD sink node writing {@code targetFqn}</li>
 *   <li>optional {@code QUALITY_GATE} over the Spark-produced DWD table</li>
 * </ul>
 *
 * <p>{@code modelId}/{@code dbtModelName} remain on the request for migration continuity and
 * naming hints, but the unified pipeline mainline no longer creates external model tasks.
 */
public record OdsDwdTemplateRequest(
        String pipelineName,
        UUID modelId,           // existing validated modeling.data_model
        String sourceFqn,       // model.source_fqn (SYNC_REF target)
        String targetFqn,       // Spark DWD sink target
        String dbtModelName,    // historical naming hint; not used as an engine selector
        boolean includeQualityGate,
        boolean includeFieldGovernance
) {}

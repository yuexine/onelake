package com.onelake.modeling.dto;

import java.util.UUID;

public record DwdModelRunRequest(
    String triggerType,
    UUID sourceIntegrationRunId,
    Boolean fullRefresh,
    String partitionStart,
    String partitionEnd
) {
    public DwdModelRunRequest(String triggerType, UUID sourceIntegrationRunId) {
        this(triggerType, sourceIntegrationRunId, null, null, null);
    }
}

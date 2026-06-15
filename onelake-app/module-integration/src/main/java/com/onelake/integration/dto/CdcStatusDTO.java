package com.onelake.integration.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CdcStatusDTO {
    private String checkpoint;
    private String status;
    private long lagMs;
    private boolean backpressure;
}

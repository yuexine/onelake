package com.onelake.orchestration.domain.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** {@link AssetTriggerReceipt} 的 DAG + 触发键复合主键。 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class AssetTriggerReceiptId implements Serializable {

    private UUID dagId;
    private String triggerKey;

    public AssetTriggerReceiptId(UUID dagId, String triggerKey) {
        this.dagId = dagId;
        this.triggerKey = triggerKey;
    }
}

package com.onelake.orchestration.domain.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** {@link AssetReadiness} 的 DAG + 任务键复合主键。 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class AssetReadinessId implements Serializable {

    private UUID dagId;
    private String taskKey;

    public AssetReadinessId(UUID dagId, String taskKey) {
        this.dagId = dagId;
        this.taskKey = taskKey;
    }
}

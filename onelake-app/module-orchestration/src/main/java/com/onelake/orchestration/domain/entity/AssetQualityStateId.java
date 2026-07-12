package com.onelake.orchestration.domain.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** {@link AssetQualityState} 的租户/资产复合主键。 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AssetQualityStateId implements Serializable {

    private UUID tenantId;
    private String assetFqn;
}

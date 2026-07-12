package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.AssetQualityState;
import com.onelake.orchestration.domain.entity.AssetQualityStateId;
import org.springframework.data.jpa.repository.JpaRepository;

/** 资产更新与质量结果持久化配对状态访问接口。 */
public interface AssetQualityStateRepository
        extends JpaRepository<AssetQualityState, AssetQualityStateId> {
}

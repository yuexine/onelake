package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.AssetReadiness;
import com.onelake.orchestration.domain.entity.AssetReadinessId;
import org.springframework.data.jpa.repository.JpaRepository;

/** 流水线输入资产就绪状态持久化接口。 */
public interface AssetReadinessRepository extends JpaRepository<AssetReadiness, AssetReadinessId> {
}

package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.AssetTriggerReceipt;
import com.onelake.orchestration.domain.entity.AssetTriggerReceiptId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 资产事件触发回执访问接口。 */
public interface AssetTriggerReceiptRepository
        extends JpaRepository<AssetTriggerReceipt, AssetTriggerReceiptId> {

    boolean existsByDagIdAndTriggerKey(UUID dagId, String triggerKey);
}

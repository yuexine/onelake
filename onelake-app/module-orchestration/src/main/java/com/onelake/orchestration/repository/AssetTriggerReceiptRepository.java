package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.AssetTriggerReceipt;
import com.onelake.orchestration.domain.entity.AssetTriggerReceiptId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 资产事件触发回执访问接口。 */
public interface AssetTriggerReceiptRepository
        extends JpaRepository<AssetTriggerReceipt, AssetTriggerReceiptId> {

    /** 只有已触发完成的回执参与消费幂等；历史 READY 行不得阻断 barrier 重建。 */
    boolean existsByDagIdAndTriggerKeyAndStatus(UUID dagId, String triggerKey, String status);
}

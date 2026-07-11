package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 流水线自动触发订阅持久化接口。 */
public interface PipelineSubscriptionRepository extends JpaRepository<PipelineSubscription, UUID> {

    /** 在租户边界内按来源定位启用的下游订阅者。 */
    List<PipelineSubscription> findByTenantIdAndSourceTypeAndSourceRefAndEnabledTrue(
            UUID tenantId, String sourceType, String sourceRef);

    /** 读取一个 DAG 的全部启用订阅，供显式订阅多输入 barrier 判定。 */
    List<PipelineSubscription> findByDagIdAndEnabledTrue(UUID dagId);
}

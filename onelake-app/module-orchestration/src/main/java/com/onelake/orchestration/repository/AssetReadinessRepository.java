package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.AssetReadiness;
import com.onelake.orchestration.domain.entity.AssetReadinessId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 流水线输入资产就绪状态持久化接口。 */
public interface AssetReadinessRepository extends JpaRepository<AssetReadiness, AssetReadinessId> {

    /** 读取一个 DAG 的持久化输入就绪集合，供多副本共享 barrier 判定。 */
    List<AssetReadiness> findByDagId(UUID dagId);

    /** SENSOR 未指定分区时只读取目标资产的最新就绪信号。 */
    Optional<AssetReadiness> findFirstByTenantIdAndAssetFqnOrderByReadyAtDesc(
            UUID tenantId, String assetFqn);

    /** SENSOR 指定分区时在数据库侧精确匹配 batchId，并只读取最新信号。 */
    Optional<AssetReadiness> findFirstByTenantIdAndAssetFqnAndBatchIdOrderByReadyAtDesc(
            UUID tenantId, String assetFqn, String batchId);

    /** 一轮输入成功汇合并触发后清空 barrier，等待下一轮资产到达。 */
    @Transactional
    void deleteByDagId(UUID dagId);

    /** 只清理一次目标节点 barrier 涉及的输入，避免影响同一 DAG 的其他汇合点。 */
    @Transactional
    void deleteByDagIdAndTaskKeyIn(UUID dagId, List<String> taskKeys);
}

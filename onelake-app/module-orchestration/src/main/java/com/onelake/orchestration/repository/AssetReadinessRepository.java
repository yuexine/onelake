package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.AssetReadiness;
import com.onelake.orchestration.domain.entity.AssetReadinessId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 流水线输入资产就绪状态持久化接口。 */
public interface AssetReadinessRepository extends JpaRepository<AssetReadiness, AssetReadinessId> {

    /** 读取一个 DAG 的持久化输入就绪集合，供多副本共享 barrier 判定。 */
    List<AssetReadiness> findByDagId(UUID dagId);

    /** 一轮输入成功汇合并触发后清空 barrier，等待下一轮资产到达。 */
    @Transactional
    void deleteByDagId(UUID dagId);
}

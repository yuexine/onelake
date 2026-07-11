package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 流水线不可变发布版本持久化访问接口。
 */
public interface PipelineVersionRepository extends JpaRepository<PipelineVersion, UUID> {

    /** 按版本号倒序返回 DAG 的发布历史。 */
    List<PipelineVersion> findByDagIdOrderByVersionDesc(UUID dagId);

    /** 按 DAG 和版本号定位一个不可变快照。 */
    Optional<PipelineVersion> findByDagIdAndVersion(UUID dagId, Integer version);

    /** 按内容哈希复用 DAG 已有的不可变版本，保证重复发布幂等。 */
    Optional<PipelineVersion> findFirstByDagIdAndChecksumOrderByVersionDesc(UUID dagId, String checksum);

    /**
     * 为一次即将发生的 Dagster reload 激活目标历史版本。
     *
     * <p>租约使用数据库 upsert，保证多后端实例共享同一激活集合；重复触发同一版本会延长
     * 有效期，而不会产生重复记录。</p>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO orchestration.pipeline_graph_activation (version_id, expires_at)
            VALUES (:versionId, :expiresAt)
            ON CONFLICT (version_id) DO UPDATE
            SET expires_at = GREATEST(
                    orchestration.pipeline_graph_activation.expires_at,
                    EXCLUDED.expires_at)
            """, nativeQuery = true)
    int activateDagsterGraphVersion(@Param("versionId") UUID versionId,
                                    @Param("expiresAt") Instant expiresAt);

    /** 清理已过期的临时定义租约，避免激活表随历史运行累积。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM orchestration.pipeline_graph_activation
            WHERE expires_at <= :now
            """, nativeQuery = true)
    int deleteExpiredDagsterGraphActivations(@Param("now") Instant now);

    /**
     * Dagster reload 只读取当前发布版本和仍在租约内的显式历史版本。
     */
    @Query(value = """
            SELECT pv.*
            FROM orchestration.pipeline_version pv
            WHERE pv.id IN (
                    SELECT d.published_version_id
                    FROM orchestration.dag d
                    WHERE d.published_version_id IS NOT NULL
                  )
               OR pv.id IN (
                    SELECT activation.version_id
                    FROM orchestration.pipeline_graph_activation activation
                    WHERE activation.expires_at > :now
                  )
            ORDER BY pv.dag_id, pv.version
            """, nativeQuery = true)
    List<PipelineVersion> findDagsterGraphDefinitionVersions(@Param("now") Instant now);
}

package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.TaskRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 节点运行持久化接口。
 *
 * <p>除详情查询外，还提供回调状态机和图短路传播所需的悲观锁查询。
 */
@Repository
public interface TaskRunRepository extends JpaRepository<TaskRun, UUID> {

    /** 查询 JobRun 下全部节点运行。 */
    List<TaskRun> findByJobRunId(UUID jobRunId);

    /** 查询 JobRun 下指定状态节点。 */
    List<TaskRun> findByJobRunIdAndStatus(UUID jobRunId, String status);

    /** 用 JobRun + taskKey 定位唯一节点运行。 */
    Optional<TaskRun> findByJobRunIdAndTaskKey(UUID jobRunId, String taskKey);

    /**
     * 节点回调状态机使用的锁定查询。
     *
     * <p>同一个 task_run 可能收到并发或乱序回调，行级写锁用于串行化
     * “读取当前状态 -> 判断是否可推进 -> 写回新状态” 的临界区。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tr from TaskRun tr where tr.jobRunId = :jobRunId and tr.taskKey = :taskKey")
    Optional<TaskRun> findByJobRunIdAndTaskKeyForUpdate(@Param("jobRunId") UUID jobRunId,
                                                        @Param("taskKey") String taskKey);

    /**
     * 短路传播和 GRAPH 终态兜底共用的批量锁定查询。
     *
     * <p>这两类逻辑都会按当前状态决定是否写回下游节点，必须等待并发节点回调先完成，
     * 避免读到旧的非终态快照后覆盖刚提交的节点终态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tr from TaskRun tr where tr.jobRunId = :jobRunId order by tr.taskKey")
    List<TaskRun> findByJobRunIdForUpdate(@Param("jobRunId") UUID jobRunId);
}

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

@Repository
public interface TaskRunRepository extends JpaRepository<TaskRun, UUID> {

    List<TaskRun> findByJobRunId(UUID jobRunId);

    List<TaskRun> findByJobRunIdAndStatus(UUID jobRunId, String status);

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

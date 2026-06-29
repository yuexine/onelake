package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.TaskRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRunRepository extends JpaRepository<TaskRun, UUID> {

    List<TaskRun> findByJobRunId(UUID jobRunId);

    List<TaskRun> findByJobRunIdAndStatus(UUID jobRunId, String status);

    Optional<TaskRun> findByJobRunIdAndTaskKey(UUID jobRunId, String taskKey);
}

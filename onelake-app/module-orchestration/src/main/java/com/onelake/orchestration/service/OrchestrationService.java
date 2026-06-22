package com.onelake.orchestration.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.domain.entity.Dag;
import com.onelake.orchestration.domain.entity.JobRun;
import com.onelake.orchestration.domain.enums.DagStatus;
import com.onelake.orchestration.domain.enums.TriggerType;
import com.onelake.orchestration.dto.DagDTO;
import com.onelake.orchestration.dto.JobRunDTO;
import com.onelake.orchestration.repository.DagRepository;
import com.onelake.orchestration.repository.JobRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private final DagRepository dagRepo;
    private final JobRunRepository runRepo;
    private final DagsterClient dagster;

    @Transactional
    public DagDTO createDag(String name, String dagsterJob, Map<String, Object> definition,
                            String scheduleCron) {
        return createDag(name, dagsterJob, definition, scheduleCron, true);
    }

    @Transactional
    public DagDTO createDag(String name, String dagsterJob, Map<String, Object> definition,
                            String scheduleCron, Boolean enabled) {
        if (name == null || name.isBlank()) {
            throw new BizException(40020, "DAG 名称不能为空");
        }
        if (definition == null || definition.isEmpty()) {
            throw new BizException(40021, "DAG definition 不能为空");
        }
        Dag dag = new Dag();
        dag.setTenantId(TenantContext.getTenantId());
        dag.setName(name.trim());
        dag.setDagsterJob(dagsterJob == null || dagsterJob.isBlank() ? "sql_workbench_draft" : dagsterJob);
        dag.setDefinition(JsonUtil.toJson(definition));
        dag.setScheduleCron(scheduleCron);
        dag.setEnabled(enabled == null ? true : enabled);
        dag.setVersion(1);
        dagRepo.save(dag);
        return toDTO(dag);
    }

    @Transactional(readOnly = true)
    public DagDTO getDag(UUID id) {
        return dagRepo.findById(id).map(this::toDTO)
            .orElseThrow(() -> new BizException(40400, "DAG 不存在"));
    }

    @Transactional(readOnly = true)
    public List<DagDTO> listDags() {
        return dagRepo.findByTenantId(TenantContext.getTenantId()).stream().map(this::toDTO).toList();
    }

    @Transactional
    public UUID triggerDag(UUID dagId, TriggerType trigger) {
        Dag dag = dagRepo.findById(dagId)
            .orElseThrow(() -> new BizException(40400, "DAG 不存在"));
        if (!Boolean.TRUE.equals(dag.getEnabled())) {
            throw new BizException(40011, "DAG 已禁用");
        }
        String dagsterRunId = dagster.launch(dag.getDagsterJob(), "onelake", "onelake-loc");

        JobRun run = new JobRun();
        run.setDagId(dagId);
        run.setDagsterRunId(dagsterRunId);
        run.setTriggerType(trigger);
        run.setStatus(DagStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setTriggeredBy(TenantContext.getUserId());
        runRepo.save(run);
        return run.getId();
    }

    @Transactional(readOnly = true)
    public Page<JobRunDTO> runs(UUID dagId, Pageable pageable) {
        return runRepo.findByDagIdOrderByStartedAtDesc(dagId, pageable)
            .map(r -> new JobRunDTO(r.getId(), r.getDagId(), r.getDagsterRunId(),
                r.getTriggerType().name(), r.getStatus().name(),
                r.getStartedAt(), r.getFinishedAt(), r.getTriggeredBy()));
    }

    private DagDTO toDTO(Dag d) {
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = d.getDefinition() == null || d.getDefinition().isBlank()
            ? Map.of()
            : JsonUtil.fromJson(d.getDefinition(), Map.class);
        return new DagDTO(d.getId(), d.getName(), d.getDagsterJob(), definition,
            d.getScheduleCron(), d.getEnabled(), d.getVersion(), d.getCreatedAt());
    }
}

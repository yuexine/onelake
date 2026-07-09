package com.onelake.orchestration.service;

import com.onelake.orchestration.client.DagsterClient;
import com.onelake.orchestration.dto.RuntimeContractDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeContractService {

    private static final String REPOSITORY = "onelake";
    private static final String LOCATION = "onelake-loc";
    private static final Set<String> PIPELINE_JOBS = Set.of("onelake_pipeline_run", "onelake_pipeline_graph_run");

    private static final List<RuntimeSpec> SPECS = List.of(
        new RuntimeSpec("SPARK", "SPARK", "onelake_pipeline_run", true, true,
            "SPARK 已接入 Dagster onelake_pipeline_run / run_spark_task_op"),
        new RuntimeSpec("SPARK", "SPARK", "onelake_pipeline_graph_run", true, true,
            "SPARK 已接入 Dagster onelake_pipeline_graph_run / run_pipeline_graph_op")
    );

    private final DagsterClient dagster;

    public List<RuntimeContractDTO> listRuntimeContracts() {
        Set<String> availableJobs = availableDagsterJobs();
        return SPECS.stream()
            .map(spec -> toDTO(spec, availableJobs.contains(spec.dagsterJob())))
            .toList();
    }

    public Optional<String> triggerBlockedReason(String dagsterJob, Map<String, Object> definition) {
        Map<String, Object> safeDefinition = definition == null ? Map.of() : definition;
        String requestedTarget = normalizeTarget(firstText(
                text(safeDefinition.get("compileTarget")), text(safeDefinition.get("engine"))));
        if (StringUtils.hasText(requestedTarget)
                && !"SPARK".equals(requestedTarget)
                && PIPELINE_JOBS.contains(dagsterJob)) {
            return Optional.of("流水线运行时已收敛为 Spark 引擎，不再支持 " + requestedTarget);
        }
        RuntimeSpec spec = specFor(dagsterJob, safeDefinition).orElse(null);
        if (spec == null) {
            return Optional.of("Dagster 作业未在运行契约中注册: " + dagsterJob);
        }
        if (spec.graphExecutionSupported()) {
            return Optional.empty();
        }
        return Optional.of(spec.blockedReason());
    }

    public Optional<String> launchBlockedReason(String dagsterJob, Map<String, Object> definition) {
        // 真正触发前额外确认 Dagster 当前 code location 暴露该 job，避免先落库再在 launch 阶段失败。
        Optional<String> contractReason = triggerBlockedReason(dagsterJob, definition);
        if (contractReason.isPresent()) {
            return contractReason;
        }
        return availableDagsterJobs().contains(dagsterJob)
                ? Optional.empty()
                : Optional.of("Dagster repository 未暴露作业: " + dagsterJob);
    }

    private Set<String> availableDagsterJobs() {
        try {
            return Set.copyOf(dagster.listJobs(REPOSITORY, LOCATION));
        } catch (RuntimeException e) {
            log.warn("Dagster runtime contract check failed: {}", e.getMessage());
            return Set.of();
        }
    }

    private RuntimeContractDTO toDTO(RuntimeSpec spec, boolean dagsterJobAvailable) {
        String status;
        String blockedReason = null;
        if (!spec.graphExecutionSupported()) {
            status = "CONTRACT_ONLY";
            blockedReason = spec.blockedReason();
        } else if (dagsterJobAvailable) {
            status = "READY";
        } else {
            status = "MISSING_DAGSTER_JOB";
            blockedReason = "Dagster repository 未暴露作业: " + spec.dagsterJob();
        }
        return new RuntimeContractDTO(
            spec.compileTarget(),
            spec.engine(),
            spec.dagsterJob(),
            spec.manifestSupported(),
            spec.graphExecutionSupported(),
            dagsterJobAvailable,
            status,
            blockedReason
        );
    }

    private Optional<RuntimeSpec> specFor(String dagsterJob, Map<String, Object> definition) {
        // 触发前校验必须优先尊重调用方实际选择的 Dagster job；compileTarget 只作为旧数据的兜底推断。
        Optional<RuntimeSpec> byJob = SPECS.stream()
            .filter(spec -> spec.dagsterJob().equals(dagsterJob))
            .findFirst();
        if (byJob.isPresent()) {
            return byJob;
        }
        String compileTarget = firstText(text(definition.get("compileTarget")), text(definition.get("engine")));
        if (!StringUtils.hasText(compileTarget) && definition.get("operatorGraph") instanceof Map<?, ?> rawGraph) {
            compileTarget = firstText(text(rawGraph.get("compileTarget")), text(rawGraph.get("engine")));
        }
        String normalizedTarget = normalizeTarget(compileTarget);
        if (StringUtils.hasText(normalizedTarget)) {
            Optional<RuntimeSpec> byTarget = SPECS.stream()
                .filter(spec -> spec.compileTarget().equals(normalizedTarget) || spec.engine().equals(normalizedTarget))
                .findFirst();
            if (byTarget.isPresent()) {
                return byTarget;
            }
        }
        return Optional.empty();
    }

    private String normalizeTarget(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("SPARK_SQL".equals(normalized) || "PYSPARK".equals(normalized)) {
            return "SPARK";
        }
        return normalized;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record RuntimeSpec(
        String compileTarget,
        String engine,
        String dagsterJob,
        boolean manifestSupported,
        boolean graphExecutionSupported,
        String blockedReason
    ) {
    }
}

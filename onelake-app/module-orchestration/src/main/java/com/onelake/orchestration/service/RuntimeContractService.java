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

/**
 * 编排运行契约服务。
 *
 * <p>该服务把前端可见的编译目标、Java 可生成的 runConfig 和 Dagster 当前暴露的 job
 * 对齐，避免用户触发尚未接入运行态的能力。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeContractService {

    private static final String REPOSITORY = "onelake";
    private static final String LOCATION = "onelake-loc";
    private static final String PIPELINE_GRAPH_JOB_PREFIX = "onelake_pipeline_graph_";

    private static final List<RuntimeSpec> SPECS = List.of(
        new RuntimeSpec("SPARK", "SPARK", "onelake_pipeline_run", true, true,
            CapabilityState.READY, null),
        new RuntimeSpec("SPARK", "SPARK", "onelake_pipeline_graph_run", true, true,
            CapabilityState.READY, null),
        new RuntimeSpec("TRINO", "TRINO", "onelake_pipeline_graph_run", true, true,
            CapabilityState.READY, null),
        new RuntimeSpec("SCRIPT", "SCRIPT", "onelake_pipeline_graph_run", true, false,
            CapabilityState.RESTRICTED,
            "脚本执行受限：目标环境必须显式启用满足 ADR-001 的隔离沙箱")
    );

    private final DagsterClient dagster;

    /** 返回静态 Java 能力与当前 Dagster code location 可用性的合并视图。 */
    public List<RuntimeContractDTO> listRuntimeContracts() {
        Set<String> availableJobs = availableDagsterJobs();
        return SPECS.stream()
            .map(spec -> toDTO(spec, availableJobs.contains(spec.dagsterJob())))
            .toList();
    }

    /**
     * 在创建运行前校验目标引擎和图执行能力是否已注册；不访问 Dagster 实时状态。
     */
    public Optional<String> triggerBlockedReason(String dagsterJob, Map<String, Object> definition) {
        Map<String, Object> safeDefinition = definition == null ? Map.of() : definition;
        RuntimeSpec spec = specFor(dagsterJob, safeDefinition).orElse(null);
        if (spec == null) {
            return Optional.of("Dagster 作业未在运行契约中注册: " + dagsterJob);
        }
        if (spec.state() != CapabilityState.READY) {
            return Optional.of(spec.blockedReason());
        }
        return spec.graphExecutionSupported()
                ? Optional.empty()
                : Optional.of(spec.blockedReason());
    }

    /**
     * 在真正 launch 前同时校验静态契约与 Dagster 当前暴露的 job，返回阻断原因。
     */
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
            log.warn("Dagster 运行契约检查失败：{}", e.getMessage());
            return Set.of();
        }
    }

    private RuntimeContractDTO toDTO(RuntimeSpec spec, boolean dagsterJobAvailable) {
        String status;
        String blockedReason = null;
        if (spec.state() != CapabilityState.READY) {
            status = spec.state().name();
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
        String compileTarget = firstText(text(definition.get("compileTarget")), text(definition.get("engine")));
        if (!StringUtils.hasText(compileTarget) && definition.get("operatorGraph") instanceof Map<?, ?> rawGraph) {
            compileTarget = firstText(text(rawGraph.get("compileTarget")), text(rawGraph.get("engine")));
        }
        String normalizedTarget = normalizeTarget(compileTarget);
        if (StringUtils.hasText(normalizedTarget)) {
            Optional<RuntimeSpec> byTarget = SPECS.stream()
                .filter(spec -> spec.compileTarget().equals(normalizedTarget) || spec.engine().equals(normalizedTarget))
                .filter(spec -> supportsJob(spec, dagsterJob))
                .findFirst();
            if (byTarget.isPresent()) {
                return byTarget;
            }
            return Optional.empty();
        }
        // 旧定义未声明 compileTarget 时，继续按实际 Dagster job 回退到 Spark 契约。
        return SPECS.stream()
                .filter(spec -> "SPARK".equals(spec.engine()))
                .filter(spec -> supportsJob(spec, dagsterJob))
                .findFirst();
    }

    private boolean supportsJob(RuntimeSpec spec, String dagsterJob) {
        return spec.dagsterJob().equals(dagsterJob)
                || (dagsterJob != null
                && dagsterJob.startsWith(PIPELINE_GRAPH_JOB_PREFIX)
                && "onelake_pipeline_graph_run".equals(spec.dagsterJob()));
    }

    private String normalizeTarget(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("SPARK_SQL".equals(normalized) || "PYSPARK".equals(normalized)) {
            return "SPARK";
        }
        if ("TRINO_SQL".equals(normalized)) {
            return "TRINO";
        }
        if ("PYTHON".equals(normalized) || "SHELL".equals(normalized)) {
            return "SCRIPT";
        }
        return normalized;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Java 侧声明的一条编译目标到 Dagster job 的支持矩阵。 */
    private record RuntimeSpec(
        String compileTarget,
        String engine,
        String dagsterJob,
        boolean manifestSupported,
        boolean graphExecutionSupported,
        CapabilityState state,
        String blockedReason
    ) {
    }

    private enum CapabilityState {
        READY,
        RESTRICTED
    }
}

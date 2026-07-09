package com.onelake.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.util.JsonUtil;
import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.TaskType;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流水线数据流校验使用的统一节点端口契约。
 *
 * <p>算子市场可以暴露更丰富的 UI 元数据，但流水线主路径需要一份编译器、校验器
 * 和运行页都能共享的运行时契约。这里保持保守：通用节点接受标准
 * {@code in/out}，结构化节点再收紧自己的端口。
 */
final class PipelineNodePortRegistry {

    private PipelineNodePortRegistry() {
    }

    static NodeContract contractFor(PipelineTask task) {
        if (task == null || task.getTaskType() == null) {
            return NodeContract.empty("UNKNOWN", "UNKNOWN");
        }
        TaskType type = task.getTaskType();
        String engine = StringUtils.hasText(task.getEngine()) ? task.getEngine() : defaultEngine(type);
        if (type == TaskType.SPARK_SQL || type == TaskType.PYSPARK) {
            String nodeKind = dataflowNodeKind(task.getConfig());
            if ("JOIN".equalsIgnoreCase(nodeKind)) {
                return new NodeContract(
                        type.name(),
                        engine,
                        Map.of(
                                "left", new InputPort("left", true, 1, 1),
                                "right", new InputPort("right", true, 1, 1)
                        ),
                        Map.of("out", new OutputPort("out")),
                        Set.of()
                );
            }
            if ("DERIVE_COLUMN".equalsIgnoreCase(nodeKind) || "SINK".equalsIgnoreCase(nodeKind)) {
                return new NodeContract(
                        type.name(),
                        engine,
                        Map.of("in", new InputPort("in", true, 1, 1)),
                        Map.of("out", new OutputPort("out")),
                        Set.of()
                );
            }
            return new NodeContract(
                    type.name(),
                    engine,
                    Map.of("in", new InputPort("in", false, 0, Integer.MAX_VALUE)),
                    Map.of("out", new OutputPort("out")),
                    Set.of()
            );
        }
        return switch (type) {
            case SYNC_REF -> new NodeContract(
                    type.name(),
                    engine,
                    Map.of(),
                    Map.of("out", new OutputPort("out")),
                    Set.of("in")
            );
            case QUALITY_GATE -> new NodeContract(
                    type.name(),
                    engine,
                    Map.of("in", new InputPort("in", false, 0, Integer.MAX_VALUE)),
                    Map.of("out", new OutputPort("out")),
                    Set.of()
            );
            default -> NodeContract.empty(type.name(), engine);
        };
    }

    private static String defaultEngine(TaskType type) {
        return switch (type) {
            case SPARK_SQL -> "SPARK_SQL";
            case PYSPARK -> "PYSPARK";
            default -> "SPARK_SQL";
        };
    }

    private static String dataflowNodeKind(String config) {
        if (!StringUtils.hasText(config)) {
            return "";
        }
        try {
            JsonNode root = JsonUtil.mapper().readTree(config);
            JsonNode value = root.path("dataflow").path("nodeKind");
            return value.isTextual() ? value.asText("") : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    record NodeContract(
            String taskType,
            String engine,
            Map<String, InputPort> inputPorts,
            Map<String, OutputPort> outputPorts,
            Set<String> ignoredInputPorts
    ) {
        static NodeContract empty(String taskType, String engine) {
            return new NodeContract(taskType, engine, Map.of(), Map.of(), Set.of());
        }

        boolean hasInputPort(String port) {
            return inputPorts.containsKey(port);
        }

        boolean ignoresInputPort(String port) {
            return ignoredInputPorts.contains(port);
        }

        boolean hasOutputPort(String port) {
            return outputPorts.containsKey(port);
        }

        List<InputPort> requiredInputs() {
            return inputPorts.values().stream().filter(InputPort::required).toList();
        }
    }

    record InputPort(String name, boolean required, int minCount, int maxCount) {
    }

    record OutputPort(String name) {
    }
}

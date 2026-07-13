package com.onelake.orchestration.dto;

import java.util.List;
import java.util.UUID;

/** 流水线编译预览，统一返回手写、自动数据流和算子生成的节点 SQL。 */
public record PipelineCompilePreview(
        UUID pipelineId,
        boolean allValidated,
        List<NodeSqlPreview> nodes,
        List<String> graphErrors
) {
    public PipelineCompilePreview {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        graphErrors = graphErrors == null ? List.of() : List.copyOf(graphErrors);
    }

    /** 单节点最终传给运行配置的 SQL/脚本预览。 */
    public record NodeSqlPreview(
            UUID taskId,
            String taskKey,
            String taskType,
            String operatorRef,
            String operatorVersion,
            String templateKind,
            String sqlOrScript,
            boolean generated,
            boolean valid,
            String errorMessage
    ) {
    }
}

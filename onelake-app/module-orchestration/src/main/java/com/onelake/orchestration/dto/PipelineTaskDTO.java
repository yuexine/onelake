package com.onelake.orchestration.dto;

import com.onelake.orchestration.domain.entity.PipelineTask;
import com.onelake.orchestration.domain.enums.TaskCompileStatus;
import com.onelake.orchestration.domain.enums.TaskCategory;
import com.onelake.orchestration.domain.enums.TaskType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@link PipelineTask} 的接口响应投影。
 *
 * @param id 节点 ID
 * @param dagId 所属流水线
 * @param taskKey 流水线内稳定节点键，也是 GRAPH 模式的 Dagster step key
 * @param taskType 节点类型
 * @param category 节点执行语义分类
 * @param operatorRef 算子市场稳定引用
 * @param operatorVersion 锁定的算子版本
 * @param name 展示名称
 * @param engine 执行引擎
 * @param targetFqn 输出表全限定名
 * @param modelId 历史模型引用
 * @param syncTaskId SYNC_REF 同步任务引用
 * @param config 结构化节点配置
 * @param compileStatus 最近编译状态
 * @param compileError 最近编译错误
 * @param executable 是否生成实际运行节点
 * @param positionX 画布横坐标
 * @param positionY 画布纵坐标
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record PipelineTaskDTO(
        UUID id,
        UUID dagId,
        String taskKey,
        TaskType taskType,
        TaskCategory category,
        String operatorRef,
        String operatorVersion,
        String name,
        String engine,
        String targetFqn,
        UUID modelId,
        UUID syncTaskId,
        Map<String, Object> config,
        TaskCompileStatus compileStatus,
        String compileError,
        Boolean executable,
        Integer positionX,
        Integer positionY,
        Instant createdAt,
        Instant updatedAt
) {
    /** 将实体中的 JSON config 解析为结构化 API 投影。 */
    public static PipelineTaskDTO of(PipelineTask t) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = t.getConfig() == null || "{}".equals(t.getConfig().trim())
                ? Map.of()
                : com.onelake.common.util.JsonUtil.fromJson(t.getConfig(), Map.class);
        return new PipelineTaskDTO(
                t.getId(),
                t.getDagId(),
                t.getTaskKey(),
                t.getTaskType(),
                t.getCategory(),
                t.getOperatorRef(),
                t.getOperatorVersion(),
                t.getName(),
                t.getEngine(),
                t.getTargetFqn(),
                t.getModelId(),
                t.getSyncTaskId(),
                cfg,
                t.getCompileStatus(),
                t.getCompileError(),
                t.getExecutable(),
                t.getPositionX(),
                t.getPositionY(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}

package com.onelake.orchestration.dto;

import java.util.List;
import java.util.Map;

/**
 * 算子 Manifest 数据传输对象。
 *
 * <p>该对象是算子注册、版本发布、图校验和前端属性面板共享的契约载体。
 *
 * @param operatorRef 跨版本稳定引用
 * @param version 语义版本
 * @param category 算子类别
 * @param scope 可见范围
 * @param displayName 展示名称
 * @param description 功能描述
 * @param icon 图标引用
 * @param tags 搜索与筛选标签
 * @param inputPorts 输入端口及其数据契约
 * @param outputSchema 输出端口/字段契约
 * @param paramsSchema 参数 JSON Schema
 * @param compileTarget 编译目标，例如 SPARK_SQL
 * @param template 运行配置模板
 * @param lineageRule 血缘生成规则
 * @param securityRule 权限与数据安全规则
 * @param qualityEmit 是否产生质量结果
 * @param policy 算子治理策略
 * @param resourceHint 默认资源提示
 * @param examples 示例参数列表
 */
public record OperatorManifestDTO(
    String operatorRef,
    String version,
    String category,
    String scope,
    String displayName,
    String description,
    String icon,
    List<String> tags,
    List<Map<String, Object>> inputPorts,
    Map<String, Object> outputSchema,
    Map<String, Object> paramsSchema,
    String compileTarget,
    Map<String, Object> template,
    Map<String, Object> lineageRule,
    Map<String, Object> securityRule,
    Boolean qualityEmit,
    Map<String, Object> policy,
    Map<String, Object> resourceHint,
    List<Map<String, Object>> examples
) {
}

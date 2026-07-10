package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 流水线三级参数，支持租户全局、流水线和节点作用域。 */
@Entity
@Table(name = "pipeline_param", schema = "orchestration")
@Getter
@Setter
public class PipelineParam {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** GLOBAL、PIPELINE 或 TASK。 */
    @Column(nullable = false, length = 16)
    private String scope;

    /** PIPELINE/TASK 参数所属流水线；GLOBAL 参数为空。 */
    private UUID dagId;

    /** TASK 参数所属节点稳定键；其他作用域为空。 */
    @Column(length = 128)
    private String taskKey;

    @Column(nullable = false, length = 128)
    private String paramKey;

    @Column(columnDefinition = "text")
    private String paramValue;

    /** STRING、NUMBER、BOOL 或 EXPR。 */
    @Column(nullable = false, length = 16)
    private String valueType = "STRING";

    @Column(length = 512)
    private String description;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}

package com.onelake.orchestration.domain.entity;

import com.onelake.orchestration.domain.enums.OperatorCategory;
import com.onelake.orchestration.domain.enums.OperatorScope;
import com.onelake.orchestration.domain.enums.OperatorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 算子主表实体。
 *
 * <p>记录算子引用、分类、作用域和当前最新版本；版本化 Manifest 存放在
 * {@link OperatorVersion} 中。
 */
@Entity
@Table(name = "operator", schema = "orchestration")
@Getter
@Setter
public class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 空表示平台级算子，非空表示租户作用域。 */
    private UUID tenantId;

    /** 稳定算子引用，是 API 和流水线配置中的业务主键。 */
    @Column(nullable = false, length = 128)
    private String operatorRef;

    /** 执行、控制或观测分类。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OperatorCategory category;

    /** BUILTIN、CUSTOM 或 TENANT_PRIVATE 可见范围。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OperatorScope scope;

    @Column(nullable = false, length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 当前发布的最新语义版本。 */
    @Column(nullable = false, length = 24)
    private String latestVersion;

    /** 生命周期状态；DEPRECATED 算子不能作为推荐新节点。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OperatorStatus status = OperatorStatus.ACTIVE;

    private Instant createdAt = Instant.now();
}

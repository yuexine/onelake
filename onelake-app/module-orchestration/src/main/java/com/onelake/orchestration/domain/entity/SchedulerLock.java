package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 调度器多副本互斥锁。
 *
 * <p>锁由 {@code expiresAt} 兜底：持有实例故障后，后续实例可以原子接管过期锁。
 */
@Entity
@Table(name = "scheduler_lock", schema = "orchestration")
@Getter
@Setter
public class SchedulerLock {

    /** 业务锁名；PipelineSchedulerService 使用固定全局键 pipeline-scheduler。 */
    @Id
    @Column(name = "lock_key", nullable = false, length = 64)
    private String lockKey;

    /** 本次持有者随机令牌；释放时必须同时匹配锁名和令牌。 */
    @Column(nullable = false, length = 128)
    private String holder;

    /** 当前持有者成功获得或接管锁的数据库时间。 */
    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    /** 租约到期时刻；实例故障后其他副本可在此时刻之后接管。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}

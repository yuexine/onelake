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

    @Id
    @Column(name = "lock_key", nullable = false, length = 64)
    private String lockKey;

    @Column(nullable = false, length = 128)
    private String holder;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}

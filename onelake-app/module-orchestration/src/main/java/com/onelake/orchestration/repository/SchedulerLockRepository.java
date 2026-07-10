package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.SchedulerLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 调度器多副本互斥锁访问接口。
 */
public interface SchedulerLockRepository extends JpaRepository<SchedulerLock, String> {

    /**
     * 原子创建锁，或在已有锁已过期时接管它。
     *
     * @return 1 表示已获得锁；0 表示锁仍由其他实例持有
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO orchestration.scheduler_lock (lock_key, holder, acquired_at, expires_at)
            VALUES (:lockKey, :holder, now(), :expiresAt)
            ON CONFLICT (lock_key) DO UPDATE
            SET holder = EXCLUDED.holder,
                acquired_at = EXCLUDED.acquired_at,
                expires_at = EXCLUDED.expires_at
            WHERE orchestration.scheduler_lock.expires_at <= now()
            """, nativeQuery = true)
    int acquire(@Param("lockKey") String lockKey,
                @Param("holder") String holder,
                @Param("expiresAt") Instant expiresAt);

    /**
     * 仅当前持有者可以释放锁，避免旧实例释放后来接管的锁。
     *
     * @return 1 表示已释放；0 表示锁不存在或持有者不匹配
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM orchestration.scheduler_lock
            WHERE lock_key = :lockKey
              AND holder = :holder
            """, nativeQuery = true)
    int release(@Param("lockKey") String lockKey, @Param("holder") String holder);
}

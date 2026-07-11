package com.onelake.common.system.repository;

import com.onelake.common.system.entity.TenantEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    /** 锁定租户稳定行，供租户级整组配置替换实现串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TenantEntity t where t.id = :id")
    Optional<TenantEntity> findByIdForUpdate(@Param("id") UUID id);
}

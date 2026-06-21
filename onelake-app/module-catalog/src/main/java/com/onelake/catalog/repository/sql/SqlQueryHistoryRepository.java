package com.onelake.catalog.repository.sql;

import com.onelake.catalog.domain.entity.sql.SqlQueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SqlQueryHistoryRepository extends JpaRepository<SqlQueryHistory, UUID> {

    List<SqlQueryHistory> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<SqlQueryHistory> findByTenantIdAndId(UUID tenantId, UUID id);
}

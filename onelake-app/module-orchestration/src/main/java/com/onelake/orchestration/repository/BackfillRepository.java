package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.Backfill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 真回填批次持久化访问接口。
 */
public interface BackfillRepository extends JpaRepository<Backfill, UUID> {
}

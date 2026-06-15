package com.onelake.integration.repository;

import com.onelake.integration.domain.entity.FileSource;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface FileSourceRepository extends JpaRepository<FileSource, UUID> {
    List<FileSource> findByTenantId(UUID tenantId);
}

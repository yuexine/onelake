package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.DataModelColumnMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DataModelColumnMappingRepository extends JpaRepository<DataModelColumnMapping, UUID> {
    List<DataModelColumnMapping> findByModelIdOrderBySortNoAsc(UUID modelId);

    void deleteByModelId(UUID modelId);
}

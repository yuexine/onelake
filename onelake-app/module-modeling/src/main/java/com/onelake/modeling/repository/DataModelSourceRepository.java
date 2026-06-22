package com.onelake.modeling.repository;

import com.onelake.modeling.domain.entity.DataModelSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DataModelSourceRepository extends JpaRepository<DataModelSource, UUID> {
    List<DataModelSource> findByModelIdOrderBySortNoAsc(UUID modelId);

    void deleteByModelId(UUID modelId);
}

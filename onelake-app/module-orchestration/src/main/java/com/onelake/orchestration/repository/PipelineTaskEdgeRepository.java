package com.onelake.orchestration.repository;

import com.onelake.orchestration.domain.entity.PipelineTaskEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PipelineTaskEdgeRepository extends JpaRepository<PipelineTaskEdge, UUID> {

    List<PipelineTaskEdge> findByDagId(UUID dagId);

    List<PipelineTaskEdge> findByDagIdAndEdgeLayer(UUID dagId, String edgeLayer);
}

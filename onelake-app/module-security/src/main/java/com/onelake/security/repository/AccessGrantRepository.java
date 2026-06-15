package com.onelake.security.repository;

import com.onelake.security.domain.entity.AccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccessGrantRepository extends JpaRepository<AccessGrant, UUID> {
    List<AccessGrant> findBySubjectIdAndStatus(UUID subjectId, String status);
    List<AccessGrant> findBySubjectId(UUID subjectId);
}

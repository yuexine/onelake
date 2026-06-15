package com.onelake.security.repository;

import com.onelake.security.domain.entity.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    List<ApprovalRequest> findByTenantIdAndStatus(UUID tenantId, String status);
    List<ApprovalRequest> findByApplicantId(UUID applicantId);
}

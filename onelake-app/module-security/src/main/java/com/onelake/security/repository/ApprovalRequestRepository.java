package com.onelake.security.repository;

import com.onelake.security.domain.entity.ApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    List<ApprovalRequest> findByTenantIdAndStatus(UUID tenantId, String status);
    Page<ApprovalRequest> findByTenantIdAndStatusInOrderByDecidedAtDescCreatedAtDesc(UUID tenantId, Collection<String> statuses, Pageable pageable);
    Page<ApprovalRequest> findByTenantIdAndApplicantIdAndStatusInOrderByCreatedAtDesc(UUID tenantId, UUID applicantId, Collection<String> statuses, Pageable pageable);
    Optional<ApprovalRequest> findFirstByTenantIdAndApplicantIdAndTargetRefAndStatusOrderByCreatedAtDesc(UUID tenantId, UUID applicantId, String targetRef, String status);
    Optional<ApprovalRequest> findFirstByTenantIdAndApplicantIdAndRequestTypeAndTargetRefAndStatusOrderByCreatedAtDesc(UUID tenantId, UUID applicantId, String requestType, String targetRef, String status);
    List<ApprovalRequest> findByTenantIdAndRequestTypeAndTargetRefAndStatus(
        UUID tenantId, String requestType, String targetRef, String status);
    Optional<ApprovalRequest> findFirstByTenantIdAndRequestTypeAndTargetRefOrderByCreatedAtDesc(
        UUID tenantId, String requestType, String targetRef);
    List<ApprovalRequest> findByApplicantId(UUID applicantId);
}

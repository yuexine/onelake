package com.onelake.security.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.common.util.JsonUtil;
import com.onelake.security.domain.entity.AccessGrant;
import com.onelake.security.domain.entity.ApprovalRequest;
import com.onelake.security.domain.entity.MaskingPolicy;
import com.onelake.security.repository.AccessGrantRepository;
import com.onelake.security.repository.ApprovalRequestRepository;
import com.onelake.security.repository.MaskingPolicyRepository;
import com.onelake.security.repository.SecretRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AccessGrantRepository grantRepo;
    private ApprovalRequestRepository approvalRepo;
    private MaskingPolicyRepository maskingRepo;
    private ObjectProvider<OutboxPublisher> outboxProvider;
    private OutboxPublisher outboxPublisher;
    private SecurityService service;

    @BeforeEach
    void setUp() {
        grantRepo = mock(AccessGrantRepository.class);
        approvalRepo = mock(ApprovalRequestRepository.class);
        maskingRepo = mock(MaskingPolicyRepository.class);
        outboxProvider = mock(ObjectProvider.class);
        outboxPublisher = mock(OutboxPublisher.class);
        service = new SecurityService(
            maskingRepo,
            grantRepo,
            approvalRepo,
            mock(SecretRepository.class),
            outboxProvider
        );
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void applyPublishReusesPendingApprovalForSameSnapshot() {
        ApprovalRequest existing = new ApprovalRequest();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(TENANT_ID);
        existing.setApplicantId(USER_ID);
        existing.setRequestType("PUBLISH");
        existing.setTargetRef("dag-1");
        existing.setPayload("{\"snapshotChecksum\":\"checksum-1\"}");
        when(approvalRepo.findByTenantIdAndRequestTypeAndTargetRefAndStatus(
            TENANT_ID, "PUBLISH", "dag-1", "PENDING")).thenReturn(List.of(existing));

        ApprovalRequest result = service.applyPublish(
            TENANT_ID, USER_ID, "dag-1", Map.of("snapshotChecksum", "checksum-1"));

        assertThat(result).isSameAs(existing);
        verify(approvalRepo, never()).save(any(ApprovalRequest.class));
    }

    @Test
    void publishApprovalDecisionEmitsResultWithRejectionReason() {
        UUID approvalId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId(approvalId);
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setRequestType("PUBLISH");
        approval.setTargetRef("dag-1");
        approval.setPayload("{\"snapshotChecksum\":\"checksum-1\",\"applicantName\":\"Data Engineer\"}");
        approval.setStatus("PENDING");
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);

        service.reject(approvalId, approverId, "risk rejected");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payloadCaptor =
            org.mockito.ArgumentCaptor.forClass(Map.class);
        assertThat(approval.getStatus()).isEqualTo("REJECTED");
        assertThat(approval.getComment()).isEqualTo("risk rejected");
        verify(outboxPublisher).publish(eq("security.approval.decided"),
            eq(approvalId.toString()), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
            .containsEntry("decision", "REJECTED")
            .containsEntry("reason", "risk rejected")
            .containsEntry("applicantName", "Data Engineer")
            .containsEntry("snapshotChecksum", "checksum-1");
    }

    @Test
    void latestPublishApprovalIsDagScopedAcrossApplicants() {
        ApprovalRequest latest = new ApprovalRequest();
        latest.setId(UUID.randomUUID());
        latest.setTenantId(TENANT_ID);
        latest.setApplicantId(UUID.randomUUID());
        latest.setRequestType("PUBLISH");
        latest.setTargetRef("dag-1");
        latest.setStatus("PENDING");
        when(approvalRepo.findFirstByTenantIdAndRequestTypeAndTargetRefOrderByCreatedAtDesc(
            TENANT_ID, "PUBLISH", "dag-1")).thenReturn(Optional.of(latest));

        ApprovalRequest result = service.latestPublishApproval("dag-1");

        assertThat(result).isSameAs(latest);
    }

    @Test
    void publishAddSignRequiresDifferentReviewerBeforeDecisionEvent() {
        UUID approvalId = UUID.randomUUID();
        UUID firstReviewer = UUID.randomUUID();
        UUID secondReviewer = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId(approvalId);
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setRequestType("PUBLISH");
        approval.setTargetRef("dag-1");
        approval.setPayload("""
            {"snapshotChecksum":"checksum-1","approvalChain":[
              {"role":"ASSET_OWNER","status":"PENDING"},
              {"role":"SECURITY_REVIEW","status":"PENDING"}
            ]}
            """);
        approval.setStatus("PENDING");
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));
        when(outboxProvider.getIfAvailable()).thenReturn(outboxPublisher);

        service.approve(approvalId, firstReviewer, "owner approved");

        assertThat(approval.getStatus()).isEqualTo("PENDING");
        assertThat(JsonUtil.parse(approval.getPayload()).path("approvalChain").get(0).path("status").asText())
            .isEqualTo("APPROVED");
        assertThat(JsonUtil.parse(approval.getPayload()).path("approvalChain").get(1).path("status").asText())
            .isEqualTo("PENDING");
        verify(outboxPublisher, never()).publish(any(), any(), any(Map.class));

        assertThatThrownBy(() -> service.approve(approvalId, firstReviewer, "self review"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("另一审批人");

        service.approve(approvalId, secondReviewer, "security approved");

        assertThat(approval.getStatus()).isEqualTo("APPROVED");
        verify(outboxPublisher).publish(eq("security.approval.decided"), eq(approvalId.toString()), any(Map.class));
    }

    @Test
    void requireQueryAccessAcceptsActiveQueryGrant() {
        when(grantRepo.findByTenantIdAndSubjectIdAndStatus(TENANT_ID, USER_ID, "ACTIVE"))
            .thenReturn(List.of(grant("ods.orders", "{\"query\":true}")));

        service.requireQueryAccess(List.of("ods.orders"));
    }

    @Test
    void myGrantsReturnsCurrentTenantActiveGrants() {
        AccessGrant grant = grant("ods.orders", "{\"query\":true}");
        when(grantRepo.findByTenantIdAndSubjectIdAndStatus(TENANT_ID, USER_ID, "ACTIVE"))
            .thenReturn(List.of(grant));

        List<AccessGrant> grants = service.myGrants();

        assertThat(grants).containsExactly(grant);
    }

    @Test
    void myGrantsExpiresAndHidesOverdueGrants() {
        AccessGrant grant = grant("ods.orders", "{\"query\":true}");
        grant.setExpiresAt(Instant.now().minusSeconds(1));
        when(grantRepo.findByTenantIdAndSubjectIdAndStatus(TENANT_ID, USER_ID, "ACTIVE"))
            .thenReturn(List.of(grant));

        List<AccessGrant> grants = service.myGrants();

        assertThat(grants).isEmpty();
        assertThat(grant.getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void requireQueryAccessRejectsMissingQueryPermission() {
        when(grantRepo.findByTenantIdAndSubjectIdAndStatus(TENANT_ID, USER_ID, "ACTIVE"))
            .thenReturn(List.of(grant("ods.orders", "{\"download\":true}")));

        assertThatThrownBy(() -> service.requireQueryAccess(List.of("ods.orders")))
            .isInstanceOf(BizException.class)
            .hasMessage("无权查询资产: ods.orders");
    }

    @Test
    void requireQueryAccessRejectsExpiredGrant() {
        AccessGrant grant = grant("ods.orders", "{\"query\":true}");
        grant.setExpiresAt(Instant.now().minusSeconds(1));
        when(grantRepo.findByTenantIdAndSubjectIdAndStatus(TENANT_ID, USER_ID, "ACTIVE"))
            .thenReturn(List.of(grant));

        assertThatThrownBy(() -> service.requireQueryAccess(List.of("ods.orders")))
            .isInstanceOf(BizException.class)
            .hasMessage("无权查询资产: ods.orders");
    }

    @Test
    void maskRowsAppliesDefaultPartialMaskForSensitiveField() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phone", "13800138000");
        row.put("amount", 99);

        SecurityService.MaskingResult result = service.maskRowsWithNotices(
            List.of(row),
            Map.of("phone", new SecurityService.FieldProtection("ods.orders.phone", "L3", "手机号", "L3"))
        );

        List<Map<String, Object>> masked = result.rows();
        assertThat(masked.get(0).get("phone")).isEqualTo("138****8000");
        assertThat(masked.get(0).get("amount")).isEqualTo(99);
        assertThat(result.maskedColumns()).containsExactly("phone");
        assertThat(result.securityNotices().get(0)).contains("phone", "L3", "手机号", "PARTIAL");
    }

    @Test
    void maskRowsUsesExplicitMaskingPolicy() {
        MaskingPolicy policy = new MaskingPolicy();
        policy.setTenantId(TENANT_ID);
        policy.setTargetFqn("ods.orders.id_card");
        policy.setStrategy("NULLIFY");
        policy.setPriority(100);
        when(maskingRepo.findByTenantIdAndTargetFqn(TENANT_ID, "ods.orders.id_card")).thenReturn(List.of(policy));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id_card", "110101199001010011");

        List<Map<String, Object>> masked = service.maskRows(
            List.of(row),
            Map.of("id_card", new SecurityService.FieldProtection("ods.orders.id_card", "L4", "身份证", "L4"))
        );

        assertThat(masked.get(0).get("id_card")).isNull();
    }

    @Test
    void approveCreatesGrantFromRequestedPermissionsAndDuration() {
        UUID approvalId = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setRequestType("ACCESS");
        approval.setTargetRef("ods.orders");
        approval.setStatus("PENDING");
        approval.setPayload("""
            {"reason":"SQL query","permissions":{"query":true,"download":true,"api":false},"durationDays":7}
            """);
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));
        when(grantRepo.save(any(AccessGrant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccessGrant grant = service.approve(approvalId, UUID.randomUUID(), "ok");

        assertThat(approval.getStatus()).isEqualTo("APPROVED");
        assertThat(grant.getAssetFqn()).isEqualTo("ods.orders");
        assertThat(grant.getPermissions()).contains("\"query\":true", "\"download\":true", "\"api\":false");
        assertThat(grant.getExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 24 * 60 * 60));
    }

    @Test
    void approveHighRiskAccessRequiresSecondApprover() {
        UUID approvalId = UUID.randomUUID();
        UUID firstApprover = UUID.randomUUID();
        UUID secondApprover = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setRequestType("ACCESS");
        approval.setTargetRef("ods.orders");
        approval.setStatus("PENDING");
        approval.setPayload("""
            {"reason":"API access","permissions":{"query":true,"download":false,"api":true},"durationDays":30,"riskLevel":"HIGH","approvalChain":[{"role":"ASSET_OWNER","status":"PENDING"},{"role":"SECURITY_REVIEW","status":"PENDING"}]}
            """);
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));
        when(grantRepo.save(any(AccessGrant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccessGrant first = service.approve(approvalId, firstApprover, "owner ok");

        assertThat(first).isNull();
        assertThat(approval.getStatus()).isEqualTo("PENDING");
        assertThat(approval.getPayload()).contains("ASSET_OWNER", "APPROVED", firstApprover.toString());

        AccessGrant finalGrant = service.approve(approvalId, secondApprover, "security ok");

        assertThat(approval.getStatus()).isEqualTo("APPROVED");
        assertThat(finalGrant.getPermissions()).contains("\"api\":true");
        assertThat(approval.getPayload()).contains("SECURITY_REVIEW", "APPROVED", secondApprover.toString());
    }

    @Test
    void approveHighRiskAccessRejectsSameSecondApprover() {
        UUID approvalId = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setRequestType("ACCESS");
        approval.setTargetRef("ods.orders");
        approval.setStatus("PENDING");
        approval.setPayload("""
            {"riskLevel":"HIGH","permissions":{"query":true,"api":true},"approvalChain":[{"role":"ASSET_OWNER","status":"APPROVED","approverId":"%s"},{"role":"SECURITY_REVIEW","status":"PENDING"}]}
            """.formatted(approver));
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.approve(approvalId, approver, "again"))
            .isInstanceOf(BizException.class)
            .hasMessage("高危权限需第二审批人复核");
    }

    @Test
    void applyAccessReusesExistingPendingApproval() {
        ApprovalRequest existing = new ApprovalRequest();
        existing.setTenantId(TENANT_ID);
        existing.setApplicantId(USER_ID);
        existing.setTargetRef("ods.orders");
        existing.setStatus("PENDING");
        when(approvalRepo.findFirstByTenantIdAndApplicantIdAndTargetRefAndStatusOrderByCreatedAtDesc(
            TENANT_ID,
            USER_ID,
            "ods.orders",
            "PENDING"
        )).thenReturn(Optional.of(existing));

        ApprovalRequest approval = service.applyAccess("ods.orders", Map.of("reason", "repeat"));

        assertThat(approval).isSameAs(existing);
        verify(approvalRepo, never()).save(any(ApprovalRequest.class));
    }

    @Test
    void applySchemaChangeCreatesApprovalOnlyRequest() {
        when(approvalRepo.findFirstByTenantIdAndApplicantIdAndRequestTypeAndTargetRefAndStatusOrderByCreatedAtDesc(
            TENANT_ID,
            USER_ID,
            "SCHEMA_CHANGE",
            "dwd.user_order_wide",
            "PENDING"
        )).thenReturn(Optional.empty());
        when(approvalRepo.save(any(ApprovalRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalRequest approval = service.applySchemaChange("dwd.user_order_wide", Map.of(
            "changeType", "DROP_COLUMN",
            "columnName", "legacy_flag",
            "reason", "字段已废弃"
        ));

        assertThat(approval.getRequestType()).isEqualTo("SCHEMA_CHANGE");
        assertThat(approval.getTargetRef()).isEqualTo("dwd.user_order_wide");
        assertThat(approval.getPayload()).contains("DROP_COLUMN", "legacy_flag", "HIGH", "APPROVAL_ONLY");
    }

    @Test
    void applySchemaChangeReusesExistingPendingSchemaChange() {
        ApprovalRequest existing = new ApprovalRequest();
        existing.setTenantId(TENANT_ID);
        existing.setApplicantId(USER_ID);
        existing.setRequestType("SCHEMA_CHANGE");
        existing.setTargetRef("dwd.user_order_wide");
        existing.setStatus("PENDING");
        when(approvalRepo.findFirstByTenantIdAndApplicantIdAndRequestTypeAndTargetRefAndStatusOrderByCreatedAtDesc(
            TENANT_ID,
            USER_ID,
            "SCHEMA_CHANGE",
            "dwd.user_order_wide",
            "PENDING"
        )).thenReturn(Optional.of(existing));

        ApprovalRequest approval = service.applySchemaChange("dwd.user_order_wide", Map.of("changeType", "ADD_COLUMN"));

        assertThat(approval).isSameAs(existing);
        verify(approvalRepo, never()).save(any(ApprovalRequest.class));
    }

    @Test
    void approveSchemaChangeDoesNotCreateAccessGrant() {
        UUID approvalId = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setRequestType("SCHEMA_CHANGE");
        approval.setTargetRef("dwd.user_order_wide");
        approval.setStatus("PENDING");
        approval.setPayload("""
            {"changeType":"ADD_COLUMN","approvalChain":[{"role":"DATA_OWNER","status":"PENDING"}]}
            """);
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));

        AccessGrant grant = service.approve(approvalId, approver, "ok");

        assertThat(grant).isNull();
        assertThat(approval.getStatus()).isEqualTo("APPROVED");
        assertThat(approval.getApproverId()).isEqualTo(approver);
        assertThat(approval.getPayload()).contains("DATA_OWNER", "APPROVED");
        verify(grantRepo, never()).save(any(AccessGrant.class));
    }

    @Test
    void processedApprovalsUsesRequestedProcessedStatus() {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setStatus("APPROVED");
        approval.setTargetRef("ods.orders");
        PageRequest pageable = PageRequest.of(0, 20);
        when(approvalRepo.findByTenantIdAndStatusInOrderByDecidedAtDescCreatedAtDesc(
            eq(TENANT_ID),
            eq(List.of("APPROVED")),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(approval), pageable, 1));

        Page<ApprovalRequest> page = service.processedApprovals("APPROVED", pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTargetRef()).isEqualTo("ods.orders");
    }

    @Test
    void processedApprovalsRejectsPendingStatus() {
        assertThatThrownBy(() -> service.processedApprovals("PENDING", PageRequest.of(0, 20)))
            .isInstanceOf(BizException.class)
            .hasMessage("审批状态不支持: PENDING");
    }

    @Test
    void myApprovalsUsesCurrentApplicantAndAllStatuses() {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setStatus("PENDING");
        approval.setTargetRef("ods.orders");
        PageRequest pageable = PageRequest.of(0, 20);
        when(approvalRepo.findByTenantIdAndApplicantIdAndStatusInOrderByCreatedAtDesc(
            eq(TENANT_ID),
            eq(USER_ID),
            eq(List.of("PENDING", "APPROVED", "REJECTED", "CANCELED")),
            eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(approval), pageable, 1));

        Page<ApprovalRequest> page = service.myApprovals("ALL", pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void myApprovalsRejectsUnsupportedStatus() {
        assertThatThrownBy(() -> service.myApprovals("ARCHIVED", PageRequest.of(0, 20)))
            .isInstanceOf(BizException.class)
            .hasMessage("审批状态不支持: ARCHIVED");
    }

    @Test
    void cancelMyApprovalMarksPendingAsCanceled() {
        UUID approvalId = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setStatus("PENDING");
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));

        service.cancelMyApproval(approvalId, "wrong asset");

        assertThat(approval.getStatus()).isEqualTo("CANCELED");
        assertThat(approval.getComment()).isEqualTo("wrong asset");
        assertThat(approval.getDecidedAt()).isNotNull();
    }

    @Test
    void cancelMyApprovalRejectsOtherApplicantsRequest() {
        UUID approvalId = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(UUID.randomUUID());
        approval.setStatus("PENDING");
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));

        assertThatThrownBy(() -> service.cancelMyApproval(approvalId, "nope"))
            .isInstanceOf(BizException.class)
            .hasMessage("无权撤回该审批单");
    }

    @Test
    void createGrantCreatesManualGrantWithDuration() {
        when(grantRepo.save(any(AccessGrant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccessGrant grant = service.createGrant(
            USER_ID,
            "ods.orders",
            Map.of("permissions", Map.of("query", true, "download", true, "api", false), "durationDays", 30)
        );

        assertThat(grant.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(grant.getSubjectId()).isEqualTo(USER_ID);
        assertThat(grant.getAssetFqn()).isEqualTo("ods.orders");
        assertThat(grant.getStatus()).isEqualTo("ACTIVE");
        assertThat(grant.getPermissions()).contains("\"download\":true");
        assertThat(grant.getExpiresAt()).isAfter(Instant.now().plusSeconds(29 * 24 * 60 * 60));
    }

    @Test
    void revokeGrantMarksGrantRevoked() {
        UUID grantId = UUID.randomUUID();
        AccessGrant grant = grant("ods.orders", "{\"query\":true}");
        when(grantRepo.findByTenantIdAndId(TENANT_ID, grantId)).thenReturn(Optional.of(grant));

        AccessGrant revoked = service.revokeGrant(grantId, "remove");

        assertThat(revoked.getStatus()).isEqualTo("REVOKED");
    }

    @Test
    void extendGrantReactivatesAndExtendsFromNowWhenExpired() {
        UUID grantId = UUID.randomUUID();
        AccessGrant grant = grant("ods.orders", "{\"query\":true}");
        grant.setStatus("EXPIRED");
        grant.setExpiresAt(Instant.now().minusSeconds(60));
        when(grantRepo.findByTenantIdAndId(TENANT_ID, grantId)).thenReturn(Optional.of(grant));

        AccessGrant extended = service.extendGrant(grantId, 7);

        assertThat(extended.getStatus()).isEqualTo("ACTIVE");
        assertThat(extended.getExpiresAt()).isAfter(Instant.now().plusSeconds(6 * 24 * 60 * 60));
    }

    @Test
    void transferApprovalStoresAssignedApprover() {
        UUID approvalId = UUID.randomUUID();
        UUID nextApprover = UUID.randomUUID();
        ApprovalRequest approval = new ApprovalRequest();
        approval.setTenantId(TENANT_ID);
        approval.setApplicantId(USER_ID);
        approval.setStatus("PENDING");
        approval.setPayload("{}");
        when(approvalRepo.findById(approvalId)).thenReturn(Optional.of(approval));

        ApprovalRequest transferred = service.transferApproval(approvalId, nextApprover, "handoff");

        assertThat(transferred.getPayload()).contains(nextApprover.toString(), "TRANSFER");
    }

    @Test
    void maskRowsChecksPoliciesForAllCandidateTargets() {
        MaskingPolicy policy = new MaskingPolicy();
        policy.setTenantId(TENANT_ID);
        policy.setTargetFqn("ods.customers.phone");
        policy.setStrategy("NULLIFY");
        policy.setPriority(100);
        when(maskingRepo.findByTenantIdAndTargetFqn(TENANT_ID, "ods.customers.phone")).thenReturn(List.of(policy));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phone", "13800138000");

        List<Map<String, Object>> masked = service.maskRows(
            List.of(row),
            Map.of("phone", new SecurityService.FieldProtection(
                "ods.orders.phone",
                "L3",
                "手机号",
                "L3",
                List.of("ods.orders.phone", "ods.customers.phone")
            ))
        );

        assertThat(masked.get(0).get("phone")).isNull();
    }

    private AccessGrant grant(String assetFqn, String permissions) {
        AccessGrant grant = new AccessGrant();
        grant.setTenantId(TENANT_ID);
        grant.setSubjectId(USER_ID);
        grant.setAssetFqn(assetFqn);
        grant.setPermissions(permissions);
        grant.setStatus("ACTIVE");
        return grant;
    }
}

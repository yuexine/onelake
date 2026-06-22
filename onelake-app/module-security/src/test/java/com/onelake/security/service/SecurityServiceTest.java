package com.onelake.security.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.security.domain.entity.AccessGrant;
import com.onelake.security.domain.entity.MaskingPolicy;
import com.onelake.security.repository.AccessGrantRepository;
import com.onelake.security.repository.ApprovalRequestRepository;
import com.onelake.security.repository.MaskingPolicyRepository;
import com.onelake.security.repository.SecretRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AccessGrantRepository grantRepo;
    private MaskingPolicyRepository maskingRepo;
    private SecurityService service;

    @BeforeEach
    void setUp() {
        grantRepo = mock(AccessGrantRepository.class);
        maskingRepo = mock(MaskingPolicyRepository.class);
        service = new SecurityService(
            maskingRepo,
            grantRepo,
            mock(ApprovalRequestRepository.class),
            mock(SecretRepository.class)
        );
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void requireQueryAccessAcceptsActiveQueryGrant() {
        when(grantRepo.findByTenantIdAndSubjectIdAndStatus(TENANT_ID, USER_ID, "ACTIVE"))
            .thenReturn(List.of(grant("ods.orders", "{\"query\":true}")));

        service.requireQueryAccess(List.of("ods.orders"));
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

package com.onelake.modeling.service;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.modeling.domain.entity.BusinessTerm;
import com.onelake.modeling.domain.entity.BusinessTermBinding;
import com.onelake.modeling.domain.entity.BusinessTermVersion;
import com.onelake.modeling.dto.BusinessTermBindingRequest;
import com.onelake.modeling.dto.BusinessTermDTO;
import com.onelake.modeling.dto.BusinessTermRequest;
import com.onelake.modeling.repository.BusinessTermBindingRepository;
import com.onelake.modeling.repository.BusinessTermRepository;
import com.onelake.modeling.repository.BusinessTermVersionRepository;
import com.onelake.modeling.repository.SubjectDomainRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlossaryServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TERM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BINDING_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private BusinessTermRepository termRepo;
    private BusinessTermBindingRepository bindingRepo;
    private BusinessTermVersionRepository versionRepo;
    private SubjectDomainRepository domainRepo;
    private OutboxPublisher outboxPublisher;
    private JdbcTemplate jdbc;
    private GlossaryService service;
    private BusinessTerm savedTerm;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        termRepo = mock(BusinessTermRepository.class);
        bindingRepo = mock(BusinessTermBindingRepository.class);
        versionRepo = mock(BusinessTermVersionRepository.class);
        domainRepo = mock(SubjectDomainRepository.class);
        outboxPublisher = mock(OutboxPublisher.class);
        jdbc = mock(JdbcTemplate.class);
        AuditLogger auditLogger = mock(AuditLogger.class);
        service = new GlossaryService(termRepo, bindingRepo, versionRepo, domainRepo, outboxPublisher, auditLogger, jdbc);

        when(termRepo.findByTenantIdAndCodeIgnoreCase(eq(TENANT_ID), anyString())).thenReturn(Optional.empty());
        when(termRepo.findByIdAndTenantId(eq(TERM_ID), eq(TENANT_ID))).thenAnswer(invocation -> Optional.ofNullable(savedTerm));
        when(bindingRepo.findByTenantIdAndTermIdOrderByCreatedAtDesc(eq(TENANT_ID), eq(TERM_ID))).thenReturn(List.of());
        doAnswer(invocation -> {
            BusinessTerm term = invocation.getArgument(0);
            term.setId(TERM_ID);
            savedTerm = term;
            return term;
        }).when(termRepo).save(any(BusinessTerm.class));
        doAnswer(invocation -> invocation.getArgument(0)).when(versionRepo).save(any(BusinessTermVersion.class));
        doAnswer(invocation -> {
            BusinessTermBinding binding = invocation.getArgument(0);
            binding.setId(BINDING_ID);
            return binding;
        }).when(bindingRepo).save(any(BusinessTermBinding.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createsApprovesAndBindsBusinessTerm() {
        BusinessTermDTO created = service.create(new BusinessTermRequest(
            "GMV",
            "成交总额",
            null,
            "一定周期内成交总额",
            "SUM(order.amount) WHERE paid",
            List.of("成交额"),
            null,
            "张三",
            null,
            "L2",
            List.of("指标")
        ));

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.version()).isEqualTo(1);
        verify(outboxPublisher).publish(eq(DomainEvents.MODELING_TERM_CREATED), eq(TERM_ID.toString()), anyMap());

        BusinessTermDTO reviewing = service.submit(TERM_ID);
        assertThat(reviewing.status()).isEqualTo("REVIEWING");

        BusinessTermDTO approved = service.approve(TERM_ID, "口径确认");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.version()).isEqualTo(1);
        assertThat(savedTerm.getApprovedBy()).isEqualTo(USER_ID);

        ArgumentCaptor<BusinessTermVersion> versionCaptor = ArgumentCaptor.forClass(BusinessTermVersion.class);
        verify(versionRepo).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersion()).isEqualTo(1);
        verify(outboxPublisher).publish(eq(DomainEvents.MODELING_TERM_APPROVED), eq(TERM_ID.toString()), anyMap());

        BusinessTermBindingRequest bindingRequest = new BusinessTermBindingRequest(
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            "ads.ads_sales_df",
            "gmv",
            "DEFINES",
            "MANUAL",
            null
        );
        assertThat(service.bind(TERM_ID, bindingRequest).assetFqn()).isEqualTo("ads.ads_sales_df");
        verify(outboxPublisher).publish(eq(DomainEvents.MODELING_TERM_BINDING_CHANGED), eq(BINDING_ID.toString()), anyMap());
    }

    @Test
    void sensitiveBindingCreatesPiiCandidate() {
        savedTerm = term("PHONE", "手机号", "APPROVED", "L3");
        when(bindingRepo.findByTenantIdAndTermIdAndAssetFqnAndColumnName(
            eq(TENANT_ID),
            eq(TERM_ID),
            eq("dwd.dwd_user_df"),
            eq("phone")
        )).thenReturn(Optional.empty());

        service.bind(TERM_ID, new BusinessTermBindingRequest(
            null,
            "dwd.dwd_user_df",
            "phone",
            "DEFINES",
            "MANUAL",
            null
        ));

        verify(jdbc).update(
            contains("security.pii_scan_record"),
            eq(TENANT_ID),
            eq("dwd.dwd_user_df.phone"),
            eq("手机号"),
            eq(0.86d),
            eq("L3")
        );
    }

    @Test
    void approvedTermUpdateCreatesGovernanceApproval() {
        savedTerm = term("GMV", "成交总额", "APPROVED", "L2");

        service.update(TERM_ID, new BusinessTermRequest(
            "GMV",
            "成交总额",
            null,
            "已支付订单成交金额",
            "SUM(order.amount) WHERE paid_at IS NOT NULL",
            List.of("成交额"),
            null,
            "张三",
            null,
            "L2",
            List.of("指标")
        ));

        verify(jdbc).update(
            contains("security.approval_request"),
            eq(TENANT_ID),
            eq(USER_ID),
            eq("business_term:" + TERM_ID),
            anyString(),
            eq(TENANT_ID),
            eq("business_term:" + TERM_ID)
        );
        assertThat(savedTerm.getStatus()).isEqualTo("REVIEWING");
    }

    private BusinessTerm term(String code, String name, String status, String sensitivityLevel) {
        BusinessTerm term = new BusinessTerm();
        term.setId(TERM_ID);
        term.setTenantId(TENANT_ID);
        term.setCode(code);
        term.setName(name);
        term.setDefinition("定义");
        term.setCaliberSql("SUM(amount)");
        term.setStatus(status);
        term.setVersion(1);
        term.setSensitivityLevel(sensitivityLevel);
        term.setOwnerName("张三");
        return term;
    }
}

package com.onelake.modeling.service;

import com.onelake.common.audit.AuditLogger;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.outbox.DomainEvents;
import com.onelake.common.outbox.OutboxPublisher;
import com.onelake.modeling.domain.entity.Codebook;
import com.onelake.modeling.domain.entity.CodebookVersion;
import com.onelake.modeling.dto.CodebookDTO;
import com.onelake.modeling.dto.CodebookEntryDTO;
import com.onelake.modeling.dto.CodebookPublishRequest;
import com.onelake.modeling.dto.CodebookRequest;
import com.onelake.modeling.repository.CodebookRepository;
import com.onelake.modeling.repository.CodebookVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodebookServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CODEBOOK_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VERSION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private CodebookRepository codebookRepo;
    private CodebookVersionRepository versionRepo;
    private OutboxPublisher outboxPublisher;
    private CodebookService service;
    private Codebook savedCodebook;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        codebookRepo = mock(CodebookRepository.class);
        versionRepo = mock(CodebookVersionRepository.class);
        outboxPublisher = mock(OutboxPublisher.class);
        AuditLogger auditLogger = mock(AuditLogger.class);
        service = new CodebookService(codebookRepo, versionRepo, outboxPublisher, auditLogger);

        when(codebookRepo.findByTenantIdAndCodeIgnoreCase(eq(TENANT_ID), anyString())).thenReturn(Optional.empty());
        when(codebookRepo.findByIdAndTenantId(eq(CODEBOOK_ID), eq(TENANT_ID))).thenAnswer(invocation -> Optional.ofNullable(savedCodebook));
        when(versionRepo.findByTenantIdAndCodebookIdAndVersionIgnoreCase(eq(TENANT_ID), eq(CODEBOOK_ID), anyString())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            Codebook codebook = invocation.getArgument(0);
            codebook.setId(CODEBOOK_ID);
            savedCodebook = codebook;
            return codebook;
        }).when(codebookRepo).save(any(Codebook.class));
        doAnswer(invocation -> {
            CodebookVersion version = invocation.getArgument(0);
            version.setId(VERSION_ID);
            return version;
        }).when(versionRepo).save(any(CodebookVersion.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createsAndPublishesCodebookVersion() {
        CodebookDTO created = service.create(new CodebookRequest(
            "core.gender",
            "性别标准字典",
            "会员",
            "统一性别编码",
            "KEEP",
            List.of(
                new CodebookEntryDTO("M", "男", null),
                new CodebookEntryDTO("F", "女", null)
            ),
            List.of("字段治理")
        ));

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.entries()).hasSize(2);
        verify(outboxPublisher).publish(eq(DomainEvents.MODELING_CODEBOOK_CREATED), eq(CODEBOOK_ID.toString()), anyMap());

        CodebookDTO published = service.publish(CODEBOOK_ID, new CodebookPublishRequest("2026.06", "初始化版本"));
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.latestVersion()).isEqualTo("2026.06");
        assertThat(published.publishedAt()).isNotNull();

        ArgumentCaptor<CodebookVersion> versionCaptor = ArgumentCaptor.forClass(CodebookVersion.class);
        verify(versionRepo).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersion()).isEqualTo("2026.06");
        assertThat(versionCaptor.getValue().getEntries()).contains("\"from\":\"M\"");
        verify(outboxPublisher).publish(eq(DomainEvents.MODELING_CODEBOOK_PUBLISHED), eq(CODEBOOK_ID.toString()), anyMap());
    }

    @Test
    void rejectsDuplicatedEntrySourceValue() {
        assertThatThrownBy(() -> service.create(new CodebookRequest(
            "core.yes_no",
            "是否字典",
            "通用",
            null,
            "KEEP",
            List.of(
                new CodebookEntryDTO("Y", "是", null),
                new CodebookEntryDTO("y", "是", null)
            ),
            List.of()
        ))).isInstanceOf(BizException.class)
            .hasMessageContaining("字典项原值重复");
    }
}

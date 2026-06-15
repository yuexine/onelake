package com.onelake.common.system.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.system.entity.ProjectEntity;
import com.onelake.common.system.entity.TenantEntity;
import com.onelake.common.system.repository.ProjectRepository;
import com.onelake.common.system.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemContextServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TenantRepository tenantRepository;
    private ProjectRepository projectRepository;
    private SystemContextService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        projectRepository = mock(ProjectRepository.class);
        service = new SystemContextService(tenantRepository, projectRepository);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUsername("dev");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
            "dev",
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("SCOPE_profile"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentContextReturnsTenantProjectsAndRoles() {
        TenantEntity tenant = tenant();
        ProjectEntity project = project("ORDER", "订单域");
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(projectRepository.findByTenantIdOrderByNameAsc(TENANT_ID)).thenReturn(List.of(project));

        var context = service.currentContext();

        assertThat(context.tenant().name()).isEqualTo("交易事业部");
        assertThat(context.projects()).hasSize(1);
        assertThat(context.projects().get(0).code()).isEqualTo("ORDER");
        assertThat(context.username()).isEqualTo("dev");
        assertThat(context.roles()).containsExactly("ADMIN", "SCOPE_profile");
    }

    @Test
    void projectsRequireTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.projects())
            .isInstanceOf(BizException.class)
            .hasMessage("租户上下文缺失");
    }

    private TenantEntity tenant() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        tenant.setCode("TRADE");
        tenant.setName("交易事业部");
        tenant.setStatus("ACTIVE");
        return tenant;
    }

    private ProjectEntity project(String code, String name) {
        ProjectEntity project = new ProjectEntity();
        project.setId(UUID.randomUUID());
        project.setTenantId(TENANT_ID);
        project.setCode(code);
        project.setName(name);
        return project;
    }
}

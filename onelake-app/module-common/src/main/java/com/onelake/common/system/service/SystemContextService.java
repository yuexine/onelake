package com.onelake.common.system.service;

import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.common.system.dto.ProjectOptionDTO;
import com.onelake.common.system.dto.SystemContextDTO;
import com.onelake.common.system.dto.TenantOptionDTO;
import com.onelake.common.system.entity.ProjectEntity;
import com.onelake.common.system.entity.TenantEntity;
import com.onelake.common.system.repository.ProjectRepository;
import com.onelake.common.system.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SystemContextService {

    private final TenantRepository tenantRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public SystemContextDTO currentContext() {
        UUID tenantId = requireTenantId();
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new BizException(40410, "当前租户不存在"));
        return new SystemContextDTO(
            toTenantDTO(tenant),
            projects(),
            TenantContext.getUserId(),
            TenantContext.getUsername(),
            roles()
        );
    }

    @Transactional(readOnly = true)
    public List<ProjectOptionDTO> projects() {
        UUID tenantId = requireTenantId();
        return projectRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
            .map(this::toProjectDTO)
            .toList();
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BizException(40100, "租户上下文缺失");
        }
        return tenantId;
    }

    private TenantOptionDTO toTenantDTO(TenantEntity tenant) {
        return new TenantOptionDTO(tenant.getId(), tenant.getCode(), tenant.getName(), tenant.getStatus());
    }

    private ProjectOptionDTO toProjectDTO(ProjectEntity project) {
        return new ProjectOptionDTO(project.getId(), project.getTenantId(), project.getCode(), project.getName());
    }

    private List<String> roles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return List.of();
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
            .sorted()
            .toList();
    }
}

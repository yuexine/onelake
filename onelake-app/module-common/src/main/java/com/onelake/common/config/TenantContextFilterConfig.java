package com.onelake.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import com.onelake.common.context.TenantContext;
import com.onelake.common.security.SecurityConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.util.UUID;

/**
 * 从 SecurityContext 提取 tenantId / userId 注入 TenantContext，
 * 同时从 X-Trace-Id header（或缺省 UUID）注入 traceId。
 * 请求结束清理 ThreadLocal。
 */
@Configuration
public class TenantContextFilterConfig {

    @Bean
    org.springframework.web.filter.OncePerRequestFilter tenantContextFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
                throws ServletException, IOException {

                String traceId = req.getHeader("X-Trace-Id");
                if (traceId == null || traceId.isBlank()) {
                    traceId = UUID.randomUUID().toString().replace("-", "");
                }
                TenantContext.setTraceId(traceId);
                resp.setHeader("X-Trace-Id", traceId);

                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                    TenantContext.setTenantId(SecurityConfig.tenantId(jwt));
                    TenantContext.setUserId(SecurityConfig.userId(jwt));
                    TenantContext.setUsername(jwt.getClaimAsString("preferred_username"));
                }

                try {
                    chain.doFilter(req, resp);
                } finally {
                    TenantContext.clear();
                }
            }
        };
    }
}

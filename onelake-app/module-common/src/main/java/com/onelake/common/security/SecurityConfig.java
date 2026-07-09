package com.onelake.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OAuth2 资源服务器配置（对应《技术初始化文档》§6.3）。
 * JWT 由 Keycloak 签发，从 realm_access.roles 解析为 Spring ROLE_*。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, InternalApiTokenFilter internalApiTokenFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(internalApiTokenFilter, BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/api/v1/internal/**",
                    "/api/v1/dataservice/apis/runtime/**",
                    "/api/v1/analytics/share/**",
                    "/error"
                ).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    @Bean
    InternalApiTokenFilter internalApiTokenFilter(
            @Value("${onelake.orchestration.internal-token:}") String internalToken) {
        return new InternalApiTokenFilter(internalToken);
    }

    /**
     * 从 Keycloak realm_access.roles 解析为 Spring 的 ROLE_*。
     */
    @SuppressWarnings("unchecked")
    private JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter defaults = new JwtGrantedAuthoritiesConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<String> realm = List.of();
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> r) {
                realm = (Collection<String>) r;
            }
            var fromScope = defaults.convert(jwt);
            var authorities = realm.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.<org.springframework.security.core.GrantedAuthority>toList());
            var merged = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
            if (fromScope != null) merged.addAll(fromScope);
            merged.addAll(authorities);
            return merged;
        });
        return conv;
    }

    /**
     * 抽取 JWT 中的常用字段，供 TenantContext 拦截器使用。
     */
    public static UUID tenantId(Jwt jwt) {
        String tid = jwt.getClaimAsString("tenant_id");
        if (tid == null) tid = jwt.getClaimAsString("tenant");
        try {
            return tid == null ? null : UUID.fromString(tid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static UUID userId(Jwt jwt) {
        String sub = jwt.getSubject();
        try {
            return sub == null ? null : UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

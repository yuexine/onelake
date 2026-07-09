package com.onelake.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内部接口共享令牌过滤器。
 *
 * <p>放在 Spring Security 过滤链前段，确保请求体反序列化和参数校验之前先完成
 * `/api/v1/internal/**` 的令牌校验，避免未授权请求触发业务层或校验错误路径。
 */
public class InternalApiTokenFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Onelake-Internal-Token";

    private final String internalToken;

    public InternalApiTokenFilter(String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isInternalApi(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 内部 token 未配置时默认拒绝，避免误把内部接口暴露成匿名可调用。
        if (!validInternalToken(request.getHeader(INTERNAL_TOKEN_HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":40300,\"message\":\"invalid internal token\",\"data\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isInternalApi(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        // 去掉 context-path 后再匹配，兼容本地根路径和网关前缀部署。
        if (StringUtils.hasText(contextPath) && path != null && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path != null && (path.equals("/api/v1/internal") || path.startsWith("/api/v1/internal/"));
    }

    private boolean validInternalToken(String providedToken) {
        if (!StringUtils.hasText(internalToken)) {
            return false;
        }
        byte[] expected = sha256(internalToken);
        byte[] provided = sha256(providedToken == null ? "" : providedToken);
        // 固定长度 digest 再比较，减少原始 token 长度差异带来的时序侧信道。
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

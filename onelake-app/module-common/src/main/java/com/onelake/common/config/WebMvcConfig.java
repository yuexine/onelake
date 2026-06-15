package com.onelake.common.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.onelake.common.context.TenantContext;

/**
 * Web MVC 装配：把 tenantContextFilter 注册为拦截器 + 启用调度 + 异步。
 */
@Configuration
@EnableScheduling
@EnableAsync
public class WebMvcConfig implements WebMvcConfigurer {

    private final org.springframework.web.filter.OncePerRequestFilter tenantContextFilter;

    public WebMvcConfig(org.springframework.web.filter.OncePerRequestFilter tenantContextFilter) {
        this.tenantContextFilter = tenantContextFilter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // traceId 同步到 MDC（filter 已注入 TenantContext）
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,
                                     jakarta.servlet.http.HttpServletResponse resp,
                                     Object handler) {
                String trace = TenantContext.getTraceId();
                MDC.put("traceId", trace);
                return true;
            }

            @Override
            public void afterCompletion(jakarta.servlet.http.HttpServletRequest req,
                                        jakarta.servlet.http.HttpServletResponse resp,
                                        Object handler, Exception ex) {
                MDC.remove("traceId");
            }
        });
    }
}

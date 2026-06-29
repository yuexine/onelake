package com.onelake.analytics.config;

import com.onelake.common.context.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 模块配置：
 * 1. @EnableAsync + @EnableScheduling 让 DatasetQueryService.logQuery 和 NotebookRunSyncScheduler 工作
 * 2. analyticsAsyncExecutor 隔离查询日志写入线程池（不阻塞主路径）
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AnalyticsConfig {

    @Bean("analyticsAsyncExecutor")
    public Executor analyticsAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("analytics-async-");
        exec.initialize();
        return exec;
    }
}

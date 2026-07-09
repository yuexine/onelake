package com.onelake.orchestration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 回填首轮派发线程池，避免阻塞创建回填的 HTTP 请求。
 */
@Configuration(proxyBeanMethods = false)
public class BackfillDispatchConfig {

    @Bean("backfillDispatchExecutor")
    public TaskExecutor backfillDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("backfill-dispatch-");
        executor.initialize();
        return executor;
    }
}

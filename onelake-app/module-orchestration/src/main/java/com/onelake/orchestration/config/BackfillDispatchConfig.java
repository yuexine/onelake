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

    /**
     * 创建有界回填派发执行器。
     *
     * <p>小线程池限制控制面并发启动压力；队列满时 BackfillDispatcher 会保留已持久化
     * 批次并交由定时恢复，而不是在调用线程退化执行。
     */
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

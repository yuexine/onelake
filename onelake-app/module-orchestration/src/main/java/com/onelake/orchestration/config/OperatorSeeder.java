package com.onelake.orchestration.config;

import com.onelake.orchestration.service.OperatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 内置算子启动种子数据写入器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperatorSeeder {

    private final OperatorService operatorService;

    /**
     * 应用就绪后幂等注册内置算子。
     *
     * <p>Flyway 或数据库尚未就绪时只记录告警，不阻断整个控制面启动；后续重启会重试。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedBuiltIns() {
        try {
            int seeded = operatorService.seedBuiltIns();
            log.info("已写入 {} 个编排内置算子", seeded);
        } catch (DataAccessException e) {
            log.warn("编排算子表尚未就绪，跳过内置算子写入：{}",
                e.getMostSpecificCause().getMessage());
        }
    }
}

package com.onelake.orchestration.config;

import com.onelake.orchestration.service.OperatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OperatorSeeder {

    private final OperatorService operatorService;

    @EventListener(ApplicationReadyEvent.class)
    public void seedBuiltIns() {
        try {
            int seeded = operatorService.seedBuiltIns();
            log.info("Seeded {} built-in orchestration operators", seeded);
        } catch (DataAccessException e) {
            log.warn("Skip built-in operator seed because orchestration operator tables are not ready: {}",
                e.getMostSpecificCause().getMessage());
        }
    }
}

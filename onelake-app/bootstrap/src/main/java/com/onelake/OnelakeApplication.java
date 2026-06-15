package com.onelake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OneLake 控制面单体启动入口。
 * 扫描所有 8 个业务模块的 com.onelake.* 包。
 */
@SpringBootApplication(scanBasePackages = "com.onelake")
@EntityScan(basePackages = "com.onelake")
@EnableJpaRepositories(basePackages = "com.onelake")
@EnableScheduling
@EnableJpaAuditing
public class OnelakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnelakeApplication.class, args);
    }
}

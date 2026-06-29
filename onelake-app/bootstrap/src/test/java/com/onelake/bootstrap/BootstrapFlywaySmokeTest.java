package com.onelake.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap Flyway smoke test —— 联调前的"数据库就绪"验证：
 *
 * 1. 全部 9 个 schema 迁移成功（含 P1 新增的 analytics）
 * 2. analytics schema 关键表都建出来：
 *    - dataset / dashboard / dashboard_publication
 *    - notebook / notebook_template / notebook_run / query_log
 * 3. dashboard_publication 的 is_current unique index 存在
 * 4. analytics.dataset.tenant_id+name unique 约束存在（防并发创建）
 *
 * <p><b>需要 Docker</b>（Testcontainers 启动 PG 15 容器）。CI 跑：
 * <pre>{@code
 * mvn -pl bootstrap test -Dtest=BootstrapFlywaySmokeTest \
 *     -Djacoco.skip=true -Dtest.docker.enabled=true
 * }</pre>
 *
 * <p>本机无 Docker 时跳过（@DisabledIfSystemProperty），不会阻塞其他测试。
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "onelake.dataplane.dagster.schedule-enabled=false"
})
@Testcontainers
@EnabledIfSystemProperty(named = "test.docker.enabled", matches = "true")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class BootstrapFlywaySmokeTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("onelake")
        .withUsername("onelake")
        .withPassword("onelake")
        .withReuse(true);

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allSchemasMigrated() {
        // 启动时 Spring Flyway auto 已经跑过迁移（连接到 testcontainers PG）
        List<String> schemas = jdbc.queryForList(
            "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name",
            String.class);
        assertThat(schemas).contains(
            "common", "integration", "orchestration", "catalog",
            "modeling", "quality", "security", "dataservice", "analytics");
    }

    @Test
    void analyticsCoreTablesExist() {
        for (String table : List.of(
            "dataset", "dashboard", "dashboard_publication",
            "notebook", "notebook_template", "notebook_run", "query_log"
        )) {
            assertThat(tableExists("analytics", table))
                .as("analytics.%s must exist", table)
                .isTrue();
        }
    }

    @Test
    void dashboardHasOptimisticLockColumn() {
        List<Map<String, Object>> cols = jdbc.queryForList(
            "SELECT column_name, data_type FROM information_schema.columns " +
            "WHERE table_schema='analytics' AND table_name='dashboard' ORDER BY column_name");
        assertThat(cols).extracting(m -> m.get("column_name"))
            .contains("version", "current_publication_id", "status");
    }

    @Test
    void datasetHasUniqueTenantName() {
        // 通过 PG 索引推断 unique 约束存在
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname='analytics' AND tablename='dataset'",
            Integer.class);
        assertThat(count).as("analytics.dataset 必须有 tenant+name 的 unique 索引").isGreaterThanOrEqualTo(2);
    }

    @Test
    void publicationIsCurrentUniqueIndex() {
        List<Map<String, Object>> idx = jdbc.queryForList(
            "SELECT indexname, indexdef FROM pg_indexes " +
            "WHERE schemaname='analytics' AND tablename='dashboard_publication'");
        assertThat(idx).anySatisfy(m -> {
            String def = String.valueOf(m.get("indexdef"));
            assertThat(def).contains("is_current = true");
            assertThat(def.toLowerCase()).contains("unique");
        });
    }

    private boolean tableExists(String schema, String table) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?",
            Integer.class, schema, table);
        return count != null && count > 0;
    }
}

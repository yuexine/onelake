package com.onelake.orchestration.domain.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止单字母后缀字段被 Hibernate 推导成错误的 offsetn 列名。 */
class PipelineDependencyMappingTest {

    @Test
    void offsetNMapsToSnakeCaseMigrationColumn() throws NoSuchFieldException {
        Column column = PipelineDependency.class
                .getDeclaredField("offsetN")
                .getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("offset_n");
    }
}

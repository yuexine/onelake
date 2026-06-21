package com.onelake.common.sql;

import com.onelake.common.exception.BizException;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlySqlValidatorTest {

    @Test
    void acceptsReadOnlyStatements() {
        assertThat(validate("SELECT * FROM ods.orders")).isInstanceOf(Select.class);
        assertThat(validate("WITH x AS (SELECT 1) SELECT * FROM x")).isInstanceOf(Select.class);
        assertThat(validate("SHOW SCHEMAS")).isInstanceOf(ShowStatement.class);
        assertThat(validate("DESCRIBE ods.orders")).isInstanceOf(DescribeStatement.class);
        assertThat(validate("EXPLAIN SELECT * FROM ods.orders")).isInstanceOf(ExplainStatement.class);
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> validate("SELECT 1; SELECT 2"))
            .isInstanceOf(BizException.class)
            .hasMessage("不允许一次提交多条语句");
    }

    @Test
    void rejectsWriteStatements() {
        assertThatThrownBy(() -> validate("INSERT INTO ods.orders SELECT * FROM tmp.orders"))
            .isInstanceOf(BizException.class)
            .hasMessage("仅允许只读查询");
        assertThatThrownBy(() -> validate("CREATE TABLE tmp.orders AS SELECT * FROM ods.orders"))
            .isInstanceOf(BizException.class)
            .hasMessage("仅允许只读查询");
        assertThatThrownBy(() -> validate("DROP TABLE ods.orders"))
            .isInstanceOf(BizException.class)
            .hasMessage("仅允许只读查询");
    }

    @Test
    void rejectsSelectInto() {
        assertThatThrownBy(() -> validate("SELECT * INTO tmp.orders FROM ods.orders"))
            .isInstanceOf(BizException.class)
            .hasMessage("仅允许只读查询");
    }

    @Test
    void rejectsExplainWithoutSelectBody() {
        assertThatThrownBy(() -> validate("EXPLAIN ods.orders"))
            .isInstanceOf(BizException.class)
            .hasMessage("仅允许只读查询");
    }

    @Test
    void rejectsInvalidSql() {
        assertThatThrownBy(() -> validate("SELECT FROM"))
            .isInstanceOf(BizException.class)
            .hasMessage("仅允许只读查询");
    }

    private Object validate(String sql) {
        return ReadOnlySqlValidator.requireSingleReadOnlyStatement(
            sql,
            40040,
            "仅允许只读查询",
            "不允许一次提交多条语句"
        );
    }
}

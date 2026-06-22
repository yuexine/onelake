package com.onelake.common.sql;

import com.onelake.common.exception.BizException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ReadOnlySqlValidator {

    private ReadOnlySqlValidator() {
    }

    public static Statement requireSingleReadOnlyStatement(
        String sql,
        int errorCode,
        String readOnlyMessage,
        String singleStatementMessage
    ) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new BizException(errorCode, readOnlyMessage, e);
        }
        List<Statement> parsed = statements.getStatements();
        if (parsed.size() != 1) {
            throw new BizException(errorCode, singleStatementMessage);
        }
        Statement statement = parsed.get(0);
        if (!isReadOnly(statement)) {
            throw new BizException(errorCode, readOnlyMessage);
        }
        return statement;
    }

    public static Set<String> referencedTables(Statement statement) {
        return new LinkedHashSet<>(new TablesNamesFinder().getTables(statement));
    }

    private static boolean isReadOnly(Statement statement) {
        if (statement instanceof ShowStatement || statement instanceof DescribeStatement) {
            return true;
        }
        if (statement instanceof ExplainStatement explain) {
            return explain.getStatement() != null && isReadOnlySelect(explain.getStatement());
        }
        return statement instanceof Select select && isReadOnlySelect(select);
    }

    private static boolean isReadOnlySelect(Select select) {
        PlainSelect plainSelect = select.getPlainSelect();
        if (plainSelect == null) {
            return true;
        }
        boolean hasIntoTables = plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty();
        return !hasIntoTables && plainSelect.getIntoTempTable() == null;
    }
}

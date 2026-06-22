package com.onelake.catalog.config;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Dedicated Trino connection provider.
 *
 * Keep the underlying pool away from Spring's DataSource auto-configuration so
 * JPA continues to use the primary Postgres datasource.
 */
public class TrinoConnectionFactory implements AutoCloseable {

    private final HikariDataSource dataSource;

    public TrinoConnectionFactory(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

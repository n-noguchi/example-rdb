package com.example.rdb.remote;

import com.example.rdb.ExampleRdb;
import org.apache.calcite.avatica.jdbc.JdbcMeta;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Avatica metadata adapter that creates Calcite connections backed by one
 * shared {@link ExampleRdb} instance.
 */
final class ExampleJdbcMeta extends JdbcMeta {

    private final ExampleRdb database;

    ExampleJdbcMeta(ExampleRdb database) throws SQLException {
        super("jdbc:calcite:");
        this.database = database;
    }

    @Override
    protected Connection createConnection(String connectionId, Properties properties) throws SQLException {
        return database.getConnection();
    }
}

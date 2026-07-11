package com.example.rdb.testclient;

import com.example.rdb.remote.ExampleAvaticaServer;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Test-only JDBC client used to exercise Example RDB through Avatica. */
public class JdbcQueryClient implements AutoCloseable {

    private final ExampleAvaticaServer ownedServer;
    private final Connection connection;
    private boolean closed;

    /** Connects to an already-running Avatica server. */
    public JdbcQueryClient(String jdbcUrl) throws SQLException {
        this.ownedServer = null;
        this.connection = DriverManager.getConnection(jdbcUrl);
    }

    /** Starts an ephemeral Avatica server backed by {@code dataDir} for an end-to-end test. */
    public JdbcQueryClient(Path dataDir) throws Exception {
        this.ownedServer = new ExampleAvaticaServer(dataDir, 0);
        ownedServer.start();
        this.connection = DriverManager.getConnection(urlFor(ownedServer.getPort()));
    }

    public void createTable(String tableName, ColumnSpec... columns) throws SQLException {
        List<String> definitions = new ArrayList<>();
        for (ColumnSpec column : columns) {
            definitions.add(column.name() + " " + column.type().getName());
        }
        execute("CREATE TABLE " + tableName + " (" + String.join(", ", definitions) + ")");
    }

    public JdbcQueryResult execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (statement.execute(sql)) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return extractResult(resultSet);
                }
            }
            return JdbcQueryResult.ofUpdate(statement.getUpdateCount());
        }
    }

    public List<JdbcQueryResult> executeScript(String... sqlStatements) throws SQLException {
        List<JdbcQueryResult> results = new ArrayList<>();
        for (String sql : sqlStatements) results.add(execute(sql));
        return results;
    }

    public void checkpoint() throws SQLException {
        execute("CHECKPOINT");
    }

    private JdbcQueryResult extractResult(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        String[] columns = new String[metadata.getColumnCount()];
        for (int i = 0; i < columns.length; i++) columns[i] = metadata.getColumnLabel(i + 1);
        List<Object[]> rows = new ArrayList<>();
        while (resultSet.next()) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < row.length; i++) row[i] = resultSet.getObject(i + 1);
            rows.add(row);
        }
        return JdbcQueryResult.ofQuery(columns, rows.toArray(new Object[0][]));
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        connection.close();
        if (ownedServer != null) ownedServer.close();
    }

    private static String urlFor(int port) {
        return "jdbc:avatica:remote:url=http://localhost:" + port + ";serialization=PROTOBUF";
    }

    public record ColumnSpec(String name, SqlTypeName type) {
        public static ColumnSpec intCol(String name) { return new ColumnSpec(name, SqlTypeName.INTEGER); }
        public static ColumnSpec bigintCol(String name) { return new ColumnSpec(name, SqlTypeName.BIGINT); }
        public static ColumnSpec varcharCol(String name) { return new ColumnSpec(name, SqlTypeName.VARCHAR); }
        public static ColumnSpec doubleCol(String name) { return new ColumnSpec(name, SqlTypeName.DOUBLE); }
        public static ColumnSpec boolCol(String name) { return new ColumnSpec(name, SqlTypeName.BOOLEAN); }
    }
}

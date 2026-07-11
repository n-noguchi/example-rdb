package com.example.rdb.support;

import com.example.rdb.ExampleRdb;
import com.example.rdb.schema.ExampleTable;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class QueryClient implements AutoCloseable {

    private final ExampleRdb rdb;
    private final Connection connection;

    public QueryClient() throws SQLException {
        this.rdb = new ExampleRdb();
        this.connection = rdb.getConnection();
    }

    public QueryClient(Path dataDir) throws SQLException {
        this.rdb = new ExampleRdb(dataDir);
        this.connection = rdb.getConnection();
    }

    public void createTable(String tableName, ColumnSpec... columns) {
        ExampleTable.ColumnDef[] defs = new ExampleTable.ColumnDef[columns.length];
        for (int i = 0; i < columns.length; i++) {
            defs[i] = new ExampleTable.ColumnDef(columns[i].name(), columns[i].type());
        }
        rdb.createTable(tableName, defs);
    }

    public QueryResult execute(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    return extractResult(rs);
                }
            } else {
                return QueryResult.ofUpdate(stmt.getUpdateCount());
            }
        }
    }

    public List<QueryResult> executeScript(String... sqlStatements) throws SQLException {
        List<QueryResult> results = new ArrayList<>();
        for (String sql : sqlStatements) {
            results.add(execute(sql));
        }
        return results;
    }

    public void checkpoint() throws IOException {
        rdb.checkpoint();
    }

    public void startAutoCheckpoint(long intervalSeconds) {
        rdb.startCheckpoint(intervalSeconds);
    }

    private QueryResult extractResult(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        String[] columnNames = new String[columnCount];
        for (int i = 0; i < columnCount; i++) {
            columnNames[i] = meta.getColumnLabel(i + 1);
        }

        List<Object[]> rowList = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                row[i] = rs.getObject(i + 1);
            }
            rowList.add(row);
        }

        return QueryResult.ofQuery(columnNames, rowList.toArray(new Object[0][]));
    }

    @Override
    public void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
        rdb.close();
    }

    public record ColumnSpec(String name, SqlTypeName type) {
        public static ColumnSpec intCol(String name) {
            return new ColumnSpec(name, SqlTypeName.INTEGER);
        }

        public static ColumnSpec bigintCol(String name) {
            return new ColumnSpec(name, SqlTypeName.BIGINT);
        }

        public static ColumnSpec varcharCol(String name) {
            return new ColumnSpec(name, SqlTypeName.VARCHAR);
        }

        public static ColumnSpec doubleCol(String name) {
            return new ColumnSpec(name, SqlTypeName.DOUBLE);
        }

        public static ColumnSpec boolCol(String name) {
            return new ColumnSpec(name, SqlTypeName.BOOLEAN);
        }
    }
}

package com.example.rdb.jdbc;

import com.example.rdb.ExampleRdb;
import com.example.rdb.schema.ExampleTable;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Handles the DDL supported by Example RDB before delegating other SQL to Calcite. */
public final class JdbcDdlSupport {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?([^\\s(]+)\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern DROP_TABLE = Pattern.compile(
            "(?is)^\\s*DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?([^\\s;]+)\\s*;?\\s*$");
    private static final Pattern CHECKPOINT = Pattern.compile("(?is)^\\s*CHECKPOINT\\s*;?\\s*$");

    private JdbcDdlSupport() {
    }

    public static Connection wrap(Connection connection, ExampleRdb database) {
        return (Connection) Proxy.newProxyInstance(
                JdbcDdlSupport.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(connection, database));
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;
        private final ExampleRdb database;

        private ConnectionHandler(Connection delegate, ExampleRdb database) {
            this.delegate = delegate;
            this.database = database;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                Object result = method.invoke(delegate, args);
                if (result instanceof Statement statement && method.getName().startsWith("createStatement")) {
                    return wrapStatement(statement, database);
                }
                return result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static Statement wrapStatement(Statement statement, ExampleRdb database) {
        return (Statement) Proxy.newProxyInstance(
                JdbcDdlSupport.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                new StatementHandler(statement, database));
    }

    private static final class StatementHandler implements InvocationHandler {
        private final Statement delegate;
        private final ExampleRdb database;
        private boolean handledDdl;

        private StatementHandler(Statement delegate, ExampleRdb database) {
            this.delegate = delegate;
            this.database = database;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isSqlExecution(method, args)) {
                if (executeDdl((String) args[0], database)) {
                    handledDdl = true;
                    return ddlExecutionResult(method.getReturnType());
                }
                handledDdl = false;
            }
            if (handledDdl) {
                switch (method.getName()) {
                    case "getUpdateCount" -> { return 0; }
                    case "getLargeUpdateCount" -> { return 0L; }
                    case "getResultSet" -> { return null; }
                    case "getMoreResults" -> { return false; }
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static boolean isSqlExecution(Method method, Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof String
                && switch (method.getName()) {
                    case "execute", "executeUpdate", "executeLargeUpdate" -> true;
                    default -> false;
                };
    }

    private static Object ddlExecutionResult(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        throw new IllegalStateException("Unexpected JDBC execution return type: " + returnType);
    }

    private static boolean executeDdl(String sql, ExampleRdb database) throws SQLException {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        if (matcher.matches()) {
            executeCreateTable(matcher, database);
            return true;
        }
        Matcher dropMatcher = DROP_TABLE.matcher(sql);
        if (dropMatcher.matches()) {
            executeDropTable(dropMatcher, database);
            return true;
        }
        if (CHECKPOINT.matcher(sql).matches()) {
            try {
                database.checkpoint();
            } catch (IOException e) {
                throw new SQLException("Checkpoint failed", "58030", e);
            }
            return true;
        }
        return false;
    }

    private static void executeCreateTable(Matcher matcher, ExampleRdb database) throws SQLException {
        boolean ifNotExists = matcher.group(1) != null;
        String tableName = unquoteIdentifier(lastIdentifierPart(matcher.group(2)));
        if (database.getSchema().getExampleTable(tableName) != null) {
            if (ifNotExists) return;
            throw new SQLException("Table already exists: " + tableName, "42S01");
        }

        CreateTableDefinition definition = parseColumns(matcher.group(3));
        if (definition.columns().isEmpty()) {
            throw new SQLException("CREATE TABLE requires at least one column", "42000");
        }
        try {
            database.createTable(tableName, definition.columns(), definition.primaryKeyColumns());
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), "42000", e);
        }
    }

    private static void executeDropTable(Matcher matcher, ExampleRdb database) throws SQLException {
        boolean ifExists = matcher.group(1) != null;
        String tableName = unquoteIdentifier(lastIdentifierPart(matcher.group(2)));
        boolean removed = database.dropTable(tableName);
        if (!removed && !ifExists) {
            throw new SQLException("Table not found: " + tableName, "42S02");
        }
    }

    private static CreateTableDefinition parseColumns(String definition) throws SQLException {
        List<ExampleTable.ColumnDef> columns = new ArrayList<>();
        List<String> primaryKeyColumns = new ArrayList<>();
        for (String columnDefinition : splitColumns(definition)) {
            String trimmed = columnDefinition.trim();
            if (isPrimaryKeyConstraint(trimmed)) {
                primaryKeyColumns.addAll(parsePrimaryKeyColumns(trimmed));
                continue;
            }
            String[] parts = columnDefinition.trim().split("\\s+", 3);
            if (parts.length < 2) {
                throw new SQLException("Invalid column definition: " + columnDefinition, "42000");
            }
            String columnName = unquoteIdentifier(parts[0]);
            columns.add(new ExampleTable.ColumnDef(columnName, parseType(parts[1])));
            if (parts.length == 3 && parts[2].toUpperCase(Locale.ROOT).contains("PRIMARY KEY")) {
                primaryKeyColumns.add(columnName);
            }
        }
        return new CreateTableDefinition(columns, primaryKeyColumns);
    }

    private static List<String> splitColumns(String definition) throws SQLException {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < definition.length(); i++) {
            char c = definition.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                columns.add(definition.substring(start, i));
                start = i + 1;
            }
            if (depth < 0) throw new SQLException("Invalid column type definition", "42000");
        }
        if (depth != 0) throw new SQLException("Invalid column type definition", "42000");
        columns.add(definition.substring(start));
        return columns;
    }

    private static SqlTypeName parseType(String token) throws SQLException {
        String type = token.replaceAll("\\(.*", "").toUpperCase(Locale.ROOT);
        return switch (type) {
            case "INT", "INTEGER" -> SqlTypeName.INTEGER;
            case "BIGINT" -> SqlTypeName.BIGINT;
            case "VARCHAR", "CHAR", "TEXT" -> SqlTypeName.VARCHAR;
            case "DOUBLE", "FLOAT", "REAL" -> SqlTypeName.DOUBLE;
            case "BOOLEAN", "BOOL" -> SqlTypeName.BOOLEAN;
            default -> throw new SQLException("Unsupported column type: " + token, "0A000");
        };
    }

    private static boolean isPrimaryKeyConstraint(String definition) {
        String normalized = definition.toUpperCase(Locale.ROOT);
        return normalized.startsWith("PRIMARY KEY")
                || (normalized.startsWith("CONSTRAINT ") && normalized.contains(" PRIMARY KEY"));
    }

    private static List<String> parsePrimaryKeyColumns(String definition) throws SQLException {
        int start = definition.indexOf('(');
        int end = definition.lastIndexOf(')');
        if (start < 0 || end <= start) {
            throw new SQLException("Invalid PRIMARY KEY constraint: " + definition, "42000");
        }
        List<String> columns = new ArrayList<>();
        for (String column : definition.substring(start + 1, end).split(",")) {
            String name = unquoteIdentifier(column);
            if (name.isEmpty()) throw new SQLException("Invalid PRIMARY KEY constraint: " + definition, "42000");
            columns.add(name);
        }
        return columns;
    }

    private static String lastIdentifierPart(String identifier) {
        int separator = identifier.lastIndexOf('.');
        return separator >= 0 ? identifier.substring(separator + 1) : identifier;
    }

    private static String unquoteIdentifier(String identifier) {
        String value = identifier.trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("`") && value.endsWith("`"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record CreateTableDefinition(List<ExampleTable.ColumnDef> columns, List<String> primaryKeyColumns) {
    }
}

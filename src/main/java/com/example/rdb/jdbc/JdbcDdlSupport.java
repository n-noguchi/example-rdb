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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdbcDdlSupport {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?([^\\s(]+)\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern DROP_TABLE = Pattern.compile(
            "(?is)^\\s*DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?([^\\s;]+)\\s*;?\\s*$");
    private static final Pattern CHECKPOINT = Pattern.compile("(?is)^\\s*CHECKPOINT\\s*;?\\s*$");
    private static final Pattern DELETE = Pattern.compile(
            "(?is)^\\s*DELETE\\s+FROM\\s+([^\\s;]+)(?:\\s+WHERE\\s+(.+?))?\\s*;?\\s*$");
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+([^\\s]+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+?))?\\s*;?\\s*$");

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
        private boolean handledIntercepted;
        private int dmlAffectedRows;

        private StatementHandler(Statement delegate, ExampleRdb database) {
            this.delegate = delegate;
            this.database = database;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isSqlExecution(method, args)) {
                int affected = executeIntercepted((String) args[0], database, delegate);
                if (affected >= 0) {
                    handledIntercepted = true;
                    dmlAffectedRows = affected;
                    return executionResult(method.getReturnType(), affected);
                }
                handledIntercepted = false;
            }
            if (handledIntercepted) {
                switch (method.getName()) {
                    case "getUpdateCount" -> { return dmlAffectedRows; }
                    case "getLargeUpdateCount" -> { return (long) dmlAffectedRows; }
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

    private static Object executionResult(Class<?> returnType, int affectedRows) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return affectedRows;
        if (returnType == long.class) return (long) affectedRows;
        throw new IllegalStateException("Unexpected JDBC execution return type: " + returnType);
    }

    private static int executeIntercepted(String sql, ExampleRdb database, Statement delegate) throws SQLException {
        Matcher createMatcher = CREATE_TABLE.matcher(sql);
        if (createMatcher.matches()) {
            executeCreateTable(createMatcher, database);
            return 0;
        }
        Matcher dropMatcher = DROP_TABLE.matcher(sql);
        if (dropMatcher.matches()) {
            executeDropTable(dropMatcher, database);
            return 0;
        }
        if (CHECKPOINT.matcher(sql).matches()) {
            try {
                database.checkpoint();
            } catch (IOException e) {
                throw new SQLException("Checkpoint failed", "58030", e);
            }
            return 0;
        }
        Matcher deleteMatcher = DELETE.matcher(sql);
        if (deleteMatcher.matches()) {
            return executeDelete(deleteMatcher, database, delegate);
        }
        Matcher updateMatcher = UPDATE.matcher(sql);
        if (updateMatcher.matches()) {
            return executeUpdateStmt(updateMatcher, database, delegate);
        }
        return -1;
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

    private static int executeDelete(Matcher matcher, ExampleRdb database, Statement delegate) throws SQLException {
        String tableName = unquoteIdentifier(lastIdentifierPart(matcher.group(1)));
        String whereClause = matcher.group(2);

        ExampleTable table = database.getSchema().getExampleTable(tableName);
        if (table == null) {
            throw new SQLException("Table not found: " + tableName, "42S02");
        }

        List<Object[]> rows = selectMatchingRows(delegate, tableName, whereClause, table);
        table.deleteRows(rows);
        return rows.size();
    }

    private static int executeUpdateStmt(Matcher matcher, ExampleRdb database, Statement delegate) throws SQLException {
        String tableName = unquoteIdentifier(lastIdentifierPart(matcher.group(1)));
        String setClause = matcher.group(2);
        String whereClause = matcher.group(3);

        ExampleTable table = database.getSchema().getExampleTable(tableName);
        if (table == null) {
            throw new SQLException("Table not found: " + tableName, "42S02");
        }

        List<SetAssignment> assignments = parseSetAssignments(setClause);
        List<Object[]> oldRows = selectMatchingRows(delegate, tableName, whereClause, table);

        List<Object[]> newRows = new ArrayList<>();
        for (Object[] oldRow : oldRows) {
            Object[] newRow = oldRow.clone();
            for (SetAssignment assignment : assignments) {
                int colIdx = table.columnIndex(assignment.column());
                if (colIdx < 0) {
                    throw new SQLException("Unknown column: " + assignment.column(), "42703");
                }
                newRow[colIdx] = evaluateExpression(assignment.expression(), oldRow, table);
            }
            newRows.add(newRow);
        }

        table.applyUpdates(oldRows, newRows);
        return oldRows.size();
    }

    private static List<Object[]> selectMatchingRows(Statement delegate, String tableName,
                                                       String whereClause, ExampleTable table) throws SQLException {
        String sql = "SELECT * FROM " + tableName;
        if (whereClause != null && !whereClause.isBlank()) {
            sql += " WHERE " + whereClause;
        }

        List<Object[]> rows = new ArrayList<>();
        try (ResultSet rs = delegate.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[colCount];
                for (int i = 0; i < colCount; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<SetAssignment> parseSetAssignments(String setClause) throws SQLException {
        List<SetAssignment> assignments = new ArrayList<>();
        for (String part : splitByComma(setClause)) {
            String trimmed = part.trim();
            int eq = findAssignmentEquals(trimmed);
            if (eq < 0) {
                throw new SQLException("Invalid SET assignment: " + part, "42000");
            }
            String column = unquoteIdentifier(trimmed.substring(0, eq).trim());
            String expression = trimmed.substring(eq + 1).trim();
            assignments.add(new SetAssignment(column, expression));
        }
        return assignments;
    }

    private static List<String> splitByComma(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) inQuote = false;
            } else if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static int findAssignmentEquals(String s) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) inQuote = false;
            } else if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '=') {
                if (i > 0 && s.charAt(i - 1) == '<' || i > 0 && s.charAt(i - 1) == '>'
                        || i > 0 && s.charAt(i - 1) == '!') {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    private static Object evaluateExpression(String expr, Object[] row, ExampleTable table) throws SQLException {
        expr = expr.trim();
        if ((expr.startsWith("'") && expr.endsWith("'"))
                || (expr.startsWith("\"") && expr.endsWith("\""))) {
            return expr.substring(1, expr.length() - 1);
        }
        if (expr.equalsIgnoreCase("NULL")) return null;
        if (expr.equalsIgnoreCase("TRUE")) return true;
        if (expr.equalsIgnoreCase("FALSE")) return false;
        try { return Integer.parseInt(expr); } catch (NumberFormatException ignored) {}
        try { return Long.parseLong(expr); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(expr); } catch (NumberFormatException ignored) {}
        int colIdx = table.columnIndex(expr);
        if (colIdx >= 0) {
            return row[colIdx];
        }
        throw new SQLException("Cannot evaluate expression: " + expr, "42000");
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

    private record SetAssignment(String column, String expression) {
    }
}

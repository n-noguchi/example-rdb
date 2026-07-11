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
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(([^)]+)\\)(?:\\s+INCLUDE\\s*\\(([^)]+)\\))?\\s*;?\\s*$");
    private static final Pattern DROP_INDEX = Pattern.compile(
            "(?is)^\\s*DROP\\s+INDEX\\s+(IF\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+(\\w+)\\s*;?\\s*$");
    private static final Pattern SELECT_EQ = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)\\s+WHERE\\s+(\\w+)\\s*=\\s*(.+?)(?:\\s+ORDER\\s+BY.*)?(?:\\s+LIMIT.*)?\\s*;?\\s*$");
    private static final Pattern SELECT_RANGE = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)\\s+WHERE\\s+(\\w+)\\s*(>=|<=|>|<)\\s*(\\S+)(?:\\s+AND\\s+\\3\\s*(>=|<=|>|<)\\s*(\\S+))?(?:\\s+ORDER\\s+BY.*)?(?:\\s+LIMIT.*)?\\s*;?\\s*$");

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
        private java.sql.ResultSet indexResultSet;

        private StatementHandler(Statement delegate, ExampleRdb database) {
            this.delegate = delegate;
            this.database = database;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isSqlExecution(method, args)) {
                String sql = (String) args[0];
                String methodName = method.getName();

                // Try index scan for SELECT
                if (methodName.equals("executeQuery") || methodName.equals("execute")) {
                    java.sql.ResultSet idxRs = tryIndexScan(sql, database, delegate);
                    if (idxRs != null) {
                        handledIntercepted = true;
                        dmlAffectedRows = -1;
                        indexResultSet = idxRs;
                        if (methodName.equals("executeQuery")) return idxRs;
                        return true; // execute() returns boolean
                    }
                }

                int affected = executeIntercepted(sql, database, delegate);
                if (affected >= 0) {
                    handledIntercepted = true;
                    dmlAffectedRows = affected;
                    indexResultSet = null;
                    return executionResult(method.getReturnType(), affected);
                }
                handledIntercepted = false;
            }
            if (handledIntercepted) {
                switch (method.getName()) {
                    case "getUpdateCount" -> { return dmlAffectedRows >= 0 ? dmlAffectedRows : -1; }
                    case "getLargeUpdateCount" -> { return (long) (dmlAffectedRows >= 0 ? dmlAffectedRows : -1); }
                    case "getResultSet" -> { return indexResultSet; }
                    case "getMoreResults" -> { indexResultSet = null; return false; }
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
                    case "execute", "executeUpdate", "executeLargeUpdate", "executeQuery" -> true;
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
        Matcher createIdxMatcher = CREATE_INDEX.matcher(sql);
        if (createIdxMatcher.matches()) {
            executeCreateIndex(createIdxMatcher, database);
            return 0;
        }
        Matcher dropIdxMatcher = DROP_INDEX.matcher(sql);
        if (dropIdxMatcher.matches()) {
            executeDropIndex(dropIdxMatcher, database);
            return 0;
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

    // ── Index DDL ──

    private static void executeCreateIndex(Matcher matcher, ExampleRdb database) throws SQLException {
        String indexName = matcher.group(1);
        String tableName = unquoteIdentifier(matcher.group(2));
        List<String> keyCols = parseColumnList(matcher.group(3));
        List<String> includeCols = matcher.group(4) != null ? parseColumnList(matcher.group(4)) : List.of();

        ExampleTable table = database.getSchema().getExampleTable(tableName);
        if (table == null) {
            throw new SQLException("Table not found: " + tableName, "42S02");
        }
        if (table.getIndexManager() != null && table.getIndexManager().hasIndex(indexName)) {
            throw new SQLException("Index already exists: " + indexName, "42P07");
        }

        try {
            database.createIndex(tableName, indexName, keyCols, includeCols);
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), "42000", e);
        }
    }

    private static void executeDropIndex(Matcher matcher, ExampleRdb database) throws SQLException {
        boolean ifExists = matcher.group(1) != null;
        String indexName = matcher.group(2);
        String tableName = unquoteIdentifier(matcher.group(3));

        ExampleTable table = database.getSchema().getExampleTable(tableName);
        if (table == null) {
            if (ifExists) return;
            throw new SQLException("Table not found: " + tableName, "42S02");
        }

        boolean dropped = database.dropIndex(tableName, indexName);
        if (!dropped && !ifExists) {
            throw new SQLException("Index not found: " + indexName, "42704");
        }
    }

    private static List<String> parseColumnList(String list) {
        List<String> cols = new ArrayList<>();
        for (String part : list.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                cols.add(unquoteIdentifier(trimmed));
            }
        }
        return cols;
    }

    // ── Index Scan Interception ──

    private static java.sql.ResultSet tryIndexScan(String sql, ExampleRdb database, Statement delegate) throws SQLException {
        Matcher eqMatcher = SELECT_EQ.matcher(sql);
        if (eqMatcher.matches()) {
            return tryEqualityIndexScan(eqMatcher, database, delegate);
        }
        Matcher rangeMatcher = SELECT_RANGE.matcher(sql);
        if (rangeMatcher.matches()) {
            return tryRangeIndexScan(rangeMatcher, database, delegate);
        }
        return null;
    }

    /**
     * Execute index scan and return results via a temporary Calcite table.
     * This avoids implementing ResultSet from scratch.
     */
    private static java.sql.ResultSet returnIndexResult(
            List<String> columnNames, List<Object[]> rows,
            ExampleRdb database, Statement delegate,
            ExampleTable table) throws SQLException {

        if (rows.isEmpty()) {
            // Return empty result via Calcite query on the original table with LIMIT 0
            String colList = String.join(",", columnNames);
            return delegate.executeQuery("SELECT " + colList + " FROM " + table.getTableName() + " WHERE 1 = 0");
        }

        // Build VALUES clause: SELECT * FROM (VALUES (...), (...)) AS t(col1, col2)
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM (VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("(");
            for (int j = 0; j < rows.get(i).length; j++) {
                if (j > 0) sql.append(", ");
                sql.append(formatSqlLiteral(rows.get(i)[j]));
            }
            sql.append(")");
        }
        sql.append(") AS t(");
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(columnNames.get(i));
        }
        sql.append(")");

        return delegate.executeQuery(sql.toString());
    }

    private static String formatSqlLiteral(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (value instanceof Number n) return n.toString();
        // String literal - escape single quotes
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private static ExampleTable.ColumnDef findColumnDefByName(ExampleTable table, String colName) {
        for (ExampleTable.ColumnDef col : table.getColumns()) {
            if (col.name.equalsIgnoreCase(colName)) return col;
        }
        return null;
    }

    private static java.sql.ResultSet tryEqualityIndexScan(Matcher matcher, ExampleRdb database, Statement delegate) throws SQLException {
        String colList = matcher.group(1).trim();
        String tableName = unquoteIdentifier(matcher.group(2));
        String whereCol = unquoteIdentifier(matcher.group(3));
        String valueStr = matcher.group(4).trim();

        ExampleTable table = database.getSchema().getExampleTable(tableName);
        if (table == null || table.getIndexManager() == null) return null;

        List<String> requestedCols = parseSelectColumns(colList);
        if (requestedCols.isEmpty()) return null;

        Object keyValue = parseLiteral(valueStr, table, whereCol);
        if (keyValue == null && !valueStr.equalsIgnoreCase("NULL")) return null;

        var scanResult = table.getIndexManager().scanCovering(whereCol, keyValue, requestedCols);
        if (scanResult == null) return null;

        List<Object[]> rows = scanResult.toRows(requestedCols);
        return returnIndexResult(requestedCols, rows, database, delegate, table);
    }

    private static java.sql.ResultSet tryRangeIndexScan(Matcher matcher, ExampleRdb database, Statement delegate) throws SQLException {
        String colList = matcher.group(1).trim();
        String tableName = unquoteIdentifier(matcher.group(2));
        String whereCol = unquoteIdentifier(matcher.group(3));
        String op1 = matcher.group(4);
        String val1 = matcher.group(5).trim();
        String op2 = matcher.group(6);
        String val2 = matcher.group(7);

        ExampleTable table = database.getSchema().getExampleTable(tableName);
        if (table == null || table.getIndexManager() == null) return null;

        List<String> requestedCols = parseSelectColumns(colList);
        if (requestedCols.isEmpty()) return null;

        // Build lower/upper bounds
        Object lower = null, upper = null;
        boolean lowerInclusive = false, upperInclusive = false;

        var bound1 = toBound(op1, val1, table, whereCol);
        if (bound1 != null) {
            if (bound1.type == BoundType.LOWER) { lower = bound1.value; lowerInclusive = bound1.inclusive; }
            else { upper = bound1.value; upperInclusive = bound1.inclusive; }
        }
        if (op2 != null && val2 != null) {
            var bound2 = toBound(op2, val2, table, whereCol);
            if (bound2 != null) {
                if (bound2.type == BoundType.LOWER) { lower = bound2.value; lowerInclusive = bound2.inclusive; }
                else { upper = bound2.value; upperInclusive = bound2.inclusive; }
            }
        }

        var scanResult = table.getIndexManager().scanRange(whereCol, lower, lowerInclusive,
                upper, upperInclusive, requestedCols);
        if (scanResult == null) return null;

        List<Object[]> rows = scanResult.toRows(requestedCols);
        return returnIndexResult(requestedCols, rows, database, delegate, table);
    }

    private enum BoundType { LOWER, UPPER }
    private record Bound(BoundType type, Object value, boolean inclusive) {}

    private static Bound toBound(String op, String valStr, ExampleTable table, String colName) {
        Object value = parseLiteral(valStr, table, colName);
        if (value == null && !valStr.trim().equalsIgnoreCase("NULL")) return null;
        return switch (op) {
            case ">" -> new Bound(BoundType.LOWER, value, false);
            case ">=" -> new Bound(BoundType.LOWER, value, true);
            case "<" -> new Bound(BoundType.UPPER, value, false);
            case "<=" -> new Bound(BoundType.UPPER, value, true);
            default -> null;
        };
    }

    private static List<String> parseSelectColumns(String colList) {
        if (colList.equals("*")) return null; // Can't determine columns for *
        List<String> cols = new ArrayList<>();
        for (String part : colList.split(",")) {
            String trimmed = part.trim();
            // Handle "col AS alias" or "table.col"
            String colName = trimmed.replaceAll("\\s+AS\\s+.*$", "").replaceAll("^\\w+\\.", "");
            cols.add(unquoteIdentifier(colName));
        }
        return cols;
    }

    private static Object parseLiteral(String valueStr, ExampleTable table, String colName) {
        valueStr = valueStr.trim();
        // Remove trailing semicolons or extra clauses
        if (valueStr.endsWith(";")) valueStr = valueStr.substring(0, valueStr.length() - 1).trim();

        if (valueStr.equalsIgnoreCase("NULL")) return null;
        if (valueStr.equalsIgnoreCase("TRUE")) return true;
        if (valueStr.equalsIgnoreCase("FALSE")) return false;

        // String literal
        if ((valueStr.startsWith("'") && valueStr.endsWith("'"))
                || (valueStr.startsWith("\"") && valueStr.endsWith("\""))) {
            return valueStr.substring(1, valueStr.length() - 1);
        }

        // Numeric
        try { return Integer.parseInt(valueStr); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(valueStr); } catch (NumberFormatException ignored) {}
        return valueStr;
    }

}

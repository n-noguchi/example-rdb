package com.example.rdb.testclient;

/** Result wrapper used by the test-only external JDBC client. */
public class JdbcQueryResult {

    private final String[] columnNames;
    private final Object[][] rows;
    private final int affectedRows;
    private final boolean query;

    private JdbcQueryResult(String[] columnNames, Object[][] rows, int affectedRows, boolean query) {
        this.columnNames = columnNames;
        this.rows = rows;
        this.affectedRows = affectedRows;
        this.query = query;
    }

    static JdbcQueryResult ofQuery(String[] columnNames, Object[][] rows) {
        return new JdbcQueryResult(columnNames, rows, 0, true);
    }

    static JdbcQueryResult ofUpdate(int affectedRows) {
        return new JdbcQueryResult(null, null, affectedRows, false);
    }

    public String[] getColumnNames() { return columnNames; }
    public Object[][] getRows() { return rows; }
    public int getRowCount() { return rows == null ? 0 : rows.length; }
    public int getAffectedRows() { return affectedRows; }
    public boolean isQuery() { return query; }
    public Object getValue(int row, int column) { return rows[row][column]; }

    public String format() {
        if (!query) return affectedRows + " row(s) affected";
        StringBuilder result = new StringBuilder();
        for (String columnName : columnNames) result.append(columnName).append('\t');
        result.append('\n');
        for (Object[] row : rows) {
            for (Object value : row) result.append(value).append('\t');
            result.append('\n');
        }
        result.append('(').append(rows.length).append(" row(s))");
        return result.toString();
    }
}

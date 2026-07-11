package com.example.rdb.support;

public class QueryResult {

    private final String[] columnNames;
    private final Object[][] rows;
    private final int affectedRows;
    private final boolean isQuery;

    private QueryResult(String[] columnNames, Object[][] rows, int affectedRows, boolean isQuery) {
        this.columnNames = columnNames;
        this.rows = rows;
        this.affectedRows = affectedRows;
        this.isQuery = isQuery;
    }

    static QueryResult ofQuery(String[] columnNames, Object[][] rows) {
        return new QueryResult(columnNames, rows, 0, true);
    }

    static QueryResult ofUpdate(int affectedRows) {
        return new QueryResult(null, null, affectedRows, false);
    }

    public String[] getColumnNames() {
        return columnNames;
    }

    public Object[][] getRows() {
        return rows;
    }

    public int getRowCount() {
        return rows != null ? rows.length : 0;
    }

    public int getAffectedRows() {
        return affectedRows;
    }

    public boolean isQuery() {
        return isQuery;
    }

    public Object getValue(int row, int col) {
        return rows[row][col];
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        if (!isQuery) {
            sb.append(affectedRows).append(" row(s) affected");
            return sb.toString();
        }

        int[] widths = new int[columnNames.length];
        for (int i = 0; i < columnNames.length; i++) {
            widths[i] = columnNames[i].length();
        }
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                int len = row[i] == null ? 4 : String.valueOf(row[i]).length();
                if (len > widths[i]) widths[i] = len;
            }
        }

        sb.append("| ");
        for (int i = 0; i < columnNames.length; i++) {
            sb.append(pad(columnNames[i], widths[i]));
            if (i < columnNames.length - 1) sb.append(" | ");
        }
        sb.append(" |\n");

        sb.append("|-");
        for (int i = 0; i < columnNames.length; i++) {
            sb.append("-".repeat(widths[i]));
            if (i < columnNames.length - 1) sb.append("-+-");
        }
        sb.append("-|\n");

        for (Object[] row : rows) {
            sb.append("| ");
            for (int i = 0; i < row.length; i++) {
                String val = row[i] == null ? "NULL" : String.valueOf(row[i]);
                sb.append(pad(val, widths[i]));
                if (i < row.length - 1) sb.append(" | ");
            }
            sb.append(" |\n");
        }

        sb.append("(").append(rows.length).append(" row(s))");
        return sb.toString();
    }

    private String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}

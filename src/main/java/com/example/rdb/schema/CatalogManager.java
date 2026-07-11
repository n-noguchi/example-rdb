package com.example.rdb.schema;

import org.apache.calcite.sql.type.SqlTypeName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CatalogManager {

    private final Path catalogPath;

    public CatalogManager(Path catalogPath) {
        this.catalogPath = catalogPath;
    }

    public void save(Iterable<ExampleTable> tables) throws IOException {
        Files.createDirectories(catalogPath.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tables\":[");
        boolean first = true;
        for (ExampleTable table : tables) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escape(table.getTableName())).append("\",\"columns\":[");
            boolean firstCol = true;
            for (ExampleTable.ColumnDef col : table.getColumns()) {
                if (!firstCol) sb.append(',');
                firstCol = false;
                sb.append("{\"name\":\"").append(escape(col.name)).append("\",\"type\":\"")
                  .append(col.typeName.getName()).append("\"}");
            }
            sb.append("],\"primaryKey\":[");
            for (int i = 0; i < table.getPrimaryKeyColumns().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("\"").append(escape(table.getPrimaryKeyColumns().get(i))).append("\"");
            }
            sb.append("]}");
        }
        sb.append("]}");
        Files.writeString(catalogPath, sb.toString(), StandardCharsets.UTF_8);
    }

    public List<TableDef> load() throws IOException {
        List<TableDef> tables = new ArrayList<>();
        if (!Files.exists(catalogPath)) {
            return tables;
        }
        String json = Files.readString(catalogPath, StandardCharsets.UTF_8);
        parseCatalog(json, tables);
        return tables;
    }

    private void parseCatalog(String json, List<TableDef> tables) {
        int pos = 0;
        pos = json.indexOf("\"tables\"", pos);
        if (pos < 0) return;
        pos = json.indexOf('[', pos);
        if (pos < 0) return;
        pos++;

        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0) break;
            String obj = json.substring(objStart, objEnd + 1);

            String name = extractString(obj, "name");
            List<ColumnDef> columns = new ArrayList<>();
            int colArrStart = obj.indexOf("\"columns\"");
            if (colArrStart >= 0) {
                int arrStart = obj.indexOf('[', colArrStart);
                int arrEnd = obj.indexOf(']', arrStart);
                String colArr = obj.substring(arrStart + 1, arrEnd);
                int cp = 0;
                while (cp < colArr.length()) {
                    int cs = colArr.indexOf('{', cp);
                    if (cs < 0) break;
                    int ce = findMatchingBrace(colArr, cs);
                    String colObj = colArr.substring(cs, ce + 1);
                    String colName = extractString(colObj, "name");
                    String colType = extractString(colObj, "type");
                    columns.add(new ColumnDef(colName, SqlTypeName.get(colType.toUpperCase())));
                    cp = ce + 1;
                }
            }

            tables.add(new TableDef(name, columns, extractStringArray(obj, "primaryKey")));
            pos = objEnd + 1;
        }
    }

    private int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private String extractString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private List<String> extractStringArray(String json, String key) {
        List<String> values = new ArrayList<>();
        int keyStart = json.indexOf("\"" + key + "\"");
        if (keyStart < 0) return values;
        int start = json.indexOf('[', keyStart);
        int end = json.indexOf(']', start);
        if (start < 0 || end < 0) return values;
        String array = json.substring(start + 1, end);
        for (String value : array.split(",")) {
            String trimmed = value.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                values.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return values;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class TableDef {
        public final String name;
        public final List<ColumnDef> columns;
        public final List<String> primaryKeyColumns;

        public TableDef(String name, List<ColumnDef> columns, List<String> primaryKeyColumns) {
            this.name = name;
            this.columns = columns;
            this.primaryKeyColumns = primaryKeyColumns;
        }
    }

    public static class ColumnDef {
        public final String name;
        public final SqlTypeName typeName;

        public ColumnDef(String name, SqlTypeName typeName) {
            this.name = name;
            this.typeName = typeName;
        }
    }
}

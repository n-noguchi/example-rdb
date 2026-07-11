package com.example.rdb.schema;

import com.example.rdb.index.IndexDefinition;
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
            sb.append("]");

            // Index definitions
            if (table.getIndexManager() != null) {
                List<IndexDefinition> indexes = table.getIndexManager().getDefinitions();
                if (!indexes.isEmpty()) {
                    sb.append(",\"indexes\":[");
                    boolean firstIdx = true;
                    for (IndexDefinition idx : indexes) {
                        if (!firstIdx) sb.append(',');
                        firstIdx = false;
                        sb.append("{\"name\":\"").append(escape(idx.name())).append("\"");
                        sb.append(",\"keyColumns\":[");
                        for (int i = 0; i < idx.keyColumns().size(); i++) {
                            if (i > 0) sb.append(',');
                            sb.append("\"").append(escape(idx.keyColumns().get(i))).append("\"");
                        }
                        sb.append("],\"includeColumns\":[");
                        for (int i = 0; i < idx.includeColumns().size(); i++) {
                            if (i > 0) sb.append(',');
                            sb.append("\"").append(escape(idx.includeColumns().get(i))).append("\"");
                        }
                        sb.append("]}");
                    }
                    sb.append("]");
                }
            }

            sb.append("}");
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
                int arrEnd = findMatchingBracket(obj, arrStart);
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

            List<String> primaryKey = extractStringArray(obj, "primaryKey");
            List<IndexDef> indexes = parseIndexes(obj);

            tables.add(new TableDef(name, columns, primaryKey, indexes));
            pos = objEnd + 1;
        }
    }

    private List<IndexDef> parseIndexes(String obj) {
        List<IndexDef> indexes = new ArrayList<>();
        int idxStart = obj.indexOf("\"indexes\"");
        if (idxStart < 0) return indexes;

        int arrStart = obj.indexOf('[', idxStart);
        int arrEnd = findMatchingBracket(obj, arrStart);
        if (arrStart < 0 || arrEnd < 0) return indexes;

        String arr = obj.substring(arrStart + 1, arrEnd);
        int cp = 0;
        while (cp < arr.length()) {
            int cs = arr.indexOf('{', cp);
            if (cs < 0) break;
            int ce = findMatchingBrace(arr, cs);
            String idxObj = arr.substring(cs, ce + 1);

            String idxName = extractString(idxObj, "name");
            List<String> keyCols = extractStringArray(idxObj, "keyColumns");
            List<String> incCols = extractStringArray(idxObj, "includeColumns");
            indexes.add(new IndexDef(idxName, keyCols, incCols));

            cp = ce + 1;
        }
        return indexes;
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

    private int findMatchingBracket(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
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
        int end = findMatchingBracket(json, start);
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
        public final List<IndexDef> indexes;

        public TableDef(String name, List<ColumnDef> columns, List<String> primaryKeyColumns) {
            this(name, columns, primaryKeyColumns, List.of());
        }

        public TableDef(String name, List<ColumnDef> columns, List<String> primaryKeyColumns,
                        List<IndexDef> indexes) {
            this.name = name;
            this.columns = columns;
            this.primaryKeyColumns = primaryKeyColumns;
            this.indexes = indexes != null ? indexes : List.of();
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

    public static class IndexDef {
        public final String name;
        public final List<String> keyColumns;
        public final List<String> includeColumns;

        public IndexDef(String name, List<String> keyColumns, List<String> includeColumns) {
            this.name = name;
            this.keyColumns = keyColumns;
            this.includeColumns = includeColumns;
        }
    }
}

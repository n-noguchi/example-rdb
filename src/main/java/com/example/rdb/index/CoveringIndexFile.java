package com.example.rdb.index;

import com.example.rdb.schema.ExampleTable;
import com.example.rdb.schema.ExampleTable.ColumnDef;
import com.example.rdb.storage.ArrowSchemaConverter;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes covering index as a sorted Arrow IPC file.
 * Schema: key columns + include columns + __row_id (BIGINT), sorted by key ASC then __row_id ASC.
 */
public class CoveringIndexFile {

    private static final String ROW_ID_COLUMN = "__row_id";
    private static final int BATCH_SIZE = 8192;

    private final BufferAllocator allocator;

    public CoveringIndexFile(BufferAllocator allocator) {
        this.allocator = allocator;
    }

    public void write(Path filePath, IndexDefinition def,
                      List<ColumnDef> tableColumns, List<CoveringEntry> entries) throws IOException {
        Files.createDirectories(filePath.getParent());
        Path tmpPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");

        List<ColumnDef> indexColumns = getIndexColumnDefs(def, tableColumns);
        Schema schema = buildIndexSchema(indexColumns);

        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
             FileOutputStream fos = new FileOutputStream(tmpPath.toFile());
             ArrowFileWriter writer = new ArrowFileWriter(root, null, fos.getChannel())) {

            writer.start();

            for (int start = 0; start < entries.size(); start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, entries.size());
                List<CoveringEntry> batch = entries.subList(start, end);

                root.clear();
                populateVectors(root, indexColumns, batch);
                root.setRowCount(batch.size());
                writer.writeBatch();
            }

            if (entries.isEmpty()) {
                root.allocateNew();
                for (FieldVector v : root.getFieldVectors()) v.setValueCount(0);
                root.setRowCount(0);
                writer.writeBatch();
            }

            writer.end();
        }

        Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public List<CoveringEntry> readAll(Path filePath, IndexDefinition def,
                                        List<ColumnDef> tableColumns) throws IOException {
        List<CoveringEntry> result = new ArrayList<>();
        if (!Files.exists(filePath)) return result;

        List<ColumnDef> indexColumns = getIndexColumnDefs(def, tableColumns);

        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             SeekableReadChannel channel = new SeekableReadChannel(fis.getChannel());
             ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {

            if (!reader.loadNextBatch()) return result;
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            extractEntries(root, indexColumns, def, tableColumns, result);
            while (reader.loadNextBatch()) {
                extractEntries(root, indexColumns, def, tableColumns, result);
            }
        }
        return result;
    }

    /**
     * Prefix scan: find all entries whose key starts with the given prefix values.
     * Used for composite indexes where only leading key columns are specified.
     */
    public List<CoveringEntry> prefixScan(Path filePath, IndexDefinition def,
                                           List<ColumnDef> tableColumns,
                                           IndexKey prefix, java.util.Set<Long> tombstones) throws IOException {
        List<CoveringEntry> all = readAll(filePath, def, tableColumns);
        List<CoveringEntry> result = new ArrayList<>();

        for (CoveringEntry entry : all) {
            if (tombstones.contains(entry.getRowId())) continue;
            if (keyStartsWith(entry.getKey(), prefix)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static boolean keyStartsWith(IndexKey key, IndexKey prefix) {
        if (key.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (compareValues(key.get(i), prefix.get(i)) != 0) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Long || b instanceof Long) return Long.compare(na.longValue(), nb.longValue());
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && b.getClass().isInstance(a)) return ca.compareTo(b);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
    public List<CoveringEntry> rangeScan(Path filePath, IndexDefinition def,
                                          List<ColumnDef> tableColumns,
                                          IndexKey lower, boolean lowerInclusive,
                                          IndexKey upper, boolean upperInclusive,
                                          java.util.Set<Long> tombstones) throws IOException {
        List<CoveringEntry> all = readAll(filePath, def, tableColumns);
        List<CoveringEntry> result = new ArrayList<>();

        for (CoveringEntry entry : all) {
            if (tombstones.contains(entry.getRowId())) continue;

            if (lower != null) {
                int cmp = entry.getKey().compareTo(lower);
                if (lowerInclusive ? cmp < 0 : cmp <= 0) continue;
            }
            if (upper != null) {
                int cmp = entry.getKey().compareTo(upper);
                if (upperInclusive ? cmp > 0 : cmp >= 0) continue;
            }
            result.add(entry);
        }
        return result;
    }

    private List<ColumnDef> getIndexColumnDefs(IndexDefinition def, List<ColumnDef> tableColumns) {
        List<ColumnDef> cols = new ArrayList<>();
        for (String keyCol : def.keyColumns()) {
            cols.add(findColumnDef(keyCol, tableColumns));
        }
        for (String incCol : def.includeColumns()) {
            if (def.keyColumns().stream().noneMatch(k -> k.equalsIgnoreCase(incCol))) {
                cols.add(findColumnDef(incCol, tableColumns));
            }
        }
        cols.add(new ColumnDef(ROW_ID_COLUMN, SqlTypeName.BIGINT));
        return cols;
    }

    private ColumnDef findColumnDef(String name, List<ColumnDef> tableColumns) {
        for (ColumnDef col : tableColumns) {
            if (col.name.equalsIgnoreCase(name)) return col;
        }
        throw new IllegalArgumentException("Column not found: " + name);
    }

    private Schema buildIndexSchema(List<ColumnDef> indexColumns) {
        List<Field> fields = new ArrayList<>();
        for (ColumnDef col : indexColumns) {
            var arrowType = ArrowSchemaConverter.toMinorType(col.typeName).getType();
            fields.add(new Field(col.name, FieldType.nullable(arrowType), null));
        }
        return new Schema(fields);
    }

    private void populateVectors(VectorSchemaRoot root, List<ColumnDef> indexColumns,
                                  List<CoveringEntry> entries) {
        for (int colIdx = 0; colIdx < indexColumns.size(); colIdx++) {
            ColumnDef col = indexColumns.get(colIdx);
            FieldVector vector = root.getVector(col.name);

            if (ROW_ID_COLUMN.equals(col.name)) {
                BigIntVector idVec = (BigIntVector) vector;
                for (int i = 0; i < entries.size(); i++) {
                    idVec.setSafe(i, entries.get(i).getRowId());
                }
                idVec.setValueCount(entries.size());
            } else {
                for (int i = 0; i < entries.size(); i++) {
                    Object value = entries.get(i).getAllValues()[colIdx];
                    setVectorValue(vector, i, col.typeName, value);
                }
                vector.setValueCount(entries.size());
            }
        }
    }

    private void setVectorValue(FieldVector vector, int index, SqlTypeName typeName, Object value) {
        if (value == null) {
            if (vector instanceof BigIntVector v) v.setNull(index);
            else if (vector instanceof org.apache.arrow.vector.IntVector v) v.setNull(index);
            else if (vector instanceof org.apache.arrow.vector.Float8Vector v) v.setNull(index);
            else if (vector instanceof org.apache.arrow.vector.VarCharVector v) v.setNull(index);
            else if (vector instanceof org.apache.arrow.vector.BitVector v) v.setNull(index);
            return;
        }
        switch (typeName) {
            case INTEGER -> ((org.apache.arrow.vector.IntVector) vector).setSafe(index, ((Number) value).intValue());
            case BIGINT -> ((BigIntVector) vector).setSafe(index, ((Number) value).longValue());
            case DOUBLE -> ((org.apache.arrow.vector.Float8Vector) vector).setSafe(index, ((Number) value).doubleValue());
            case VARCHAR -> ((org.apache.arrow.vector.VarCharVector) vector).setSafe(index, value.toString().getBytes(StandardCharsets.UTF_8));
            case BOOLEAN -> ((org.apache.arrow.vector.BitVector) vector).setSafe(index, (Boolean) value ? 1 : 0);
            default -> ((org.apache.arrow.vector.VarCharVector) vector).setSafe(index, value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void extractEntries(VectorSchemaRoot root, List<ColumnDef> indexColumns,
                                 IndexDefinition def, List<ColumnDef> tableColumns,
                                 List<CoveringEntry> result) {
        int rowCount = root.getRowCount();
        int keyCount = def.keyColumns().size();
        int valueCount = indexColumns.size() - 1; // exclude __row_id

        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            Object[] allValues = new Object[valueCount];
            for (int colIdx = 0; colIdx < valueCount; colIdx++) {
                ColumnDef col = indexColumns.get(colIdx);
                FieldVector vector = root.getVector(col.name);
                allValues[colIdx] = extractValue(vector, rowIdx, col.typeName);
            }

            BigIntVector idVec = (BigIntVector) root.getVector(ROW_ID_COLUMN);
            long rowId = idVec.get(rowIdx);

            Object[] keyValues = new Object[keyCount];
            System.arraycopy(allValues, 0, keyValues, 0, keyCount);

            result.add(new CoveringEntry(rowId, new IndexKey(keyValues), allValues));
        }
    }

    private Object extractValue(FieldVector vector, int index, SqlTypeName typeName) {
        if (vector.isNull(index)) return null;
        switch (typeName) {
            case INTEGER -> { return ((org.apache.arrow.vector.IntVector) vector).get(index); }
            case BIGINT -> { return ((BigIntVector) vector).get(index); }
            case DOUBLE -> { return ((org.apache.arrow.vector.Float8Vector) vector).get(index); }
            case VARCHAR -> {
                byte[] bytes = ((org.apache.arrow.vector.VarCharVector) vector).get(index);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            case BOOLEAN -> { return ((org.apache.arrow.vector.BitVector) vector).get(index) == 1; }
            default -> {
                byte[] bytes = ((org.apache.arrow.vector.VarCharVector) vector).get(index);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }
}

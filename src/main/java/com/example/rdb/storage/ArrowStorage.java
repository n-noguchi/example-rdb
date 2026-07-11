package com.example.rdb.storage;

import com.example.rdb.schema.ExampleTable;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
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

public class ArrowStorage {

    static final int BATCH_SIZE = 8192;

    private final BufferAllocator allocator;

    public ArrowStorage() {
        this.allocator = new RootAllocator(Integer.MAX_VALUE);
    }

    public ArrowStorage(BufferAllocator allocator) {
        this.allocator = allocator;
    }

    public void writeTable(Path filePath, ExampleTable table) throws IOException {
        writeRowsBatched(filePath, table.getRows(), table.getColumns());
    }

    public void writeRows(Path filePath, List<Object[]> rows, List<ExampleTable.ColumnDef> columns) throws IOException {
        writeRowsBatched(filePath, rows, columns);
    }

    public void writeMergedTable(Path filePath, Path basePath, List<Object[]> deltaRows,
                                 List<ExampleTable.ColumnDef> columns) throws IOException {
        List<Object[]> allRows = new ArrayList<>();
        if (basePath != null && Files.exists(basePath)) {
            allRows.addAll(readTable(basePath, columns));
        }
        allRows.addAll(deltaRows);
        writeRowsBatched(filePath, allRows, columns);
    }

    private void writeRowsBatched(Path filePath, List<Object[]> rows,
                                  List<ExampleTable.ColumnDef> columns) throws IOException {
        Files.createDirectories(filePath.getParent());
        Schema schema = ArrowSchemaConverter.toArrowSchema(columns);
        Path tmpPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");

        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
             FileOutputStream fos = new FileOutputStream(tmpPath.toFile());
             ArrowFileWriter writer = new ArrowFileWriter(root, null, fos.getChannel())) {

            writer.start();

            if (rows.isEmpty()) {
                root.allocateNew();
                for (FieldVector v : root.getFieldVectors()) {
                    v.setValueCount(0);
                }
                root.setRowCount(0);
                writer.writeBatch();
            } else {
                for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
                    int end = Math.min(start + BATCH_SIZE, rows.size());
                    List<Object[]> batch = rows.subList(start, end);
                    root.clear();
                    populateVectors(root, columns, batch);
                    root.setRowCount(batch.size());
                    writer.writeBatch();
                }
            }

            writer.end();
        }

        Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public List<Object[]> readTable(Path filePath, List<ExampleTable.ColumnDef> columns) throws IOException {
        List<Object[]> rows = new ArrayList<>();
        if (!Files.exists(filePath)) {
            return rows;
        }

        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             SeekableReadChannel channel = new SeekableReadChannel(fis.getChannel());
             ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {

            if (reader.loadNextBatch()) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                extractRows(root, columns, rows);
                while (reader.loadNextBatch()) {
                    extractRows(root, columns, rows);
                }
            }
        }

        return rows;
    }

    public long countRows(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            return 0;
        }
        long count = 0;
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             SeekableReadChannel channel = new SeekableReadChannel(fis.getChannel());
             ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {
            while (reader.loadNextBatch()) {
                count += reader.getVectorSchemaRoot().getRowCount();
            }
        }
        return count;
    }

    private void populateVectors(VectorSchemaRoot root, List<ExampleTable.ColumnDef> columns, List<Object[]> rows) {
        for (int colIdx = 0; colIdx < columns.size(); colIdx++) {
            ExampleTable.ColumnDef col = columns.get(colIdx);
            FieldVector vector = root.getVector(col.name);
            for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                Object value = rows.get(rowIdx)[colIdx];
                setVectorValue(vector, rowIdx, col.typeName, value);
            }
            vector.setValueCount(rows.size());
        }
    }

    private void setVectorValue(FieldVector vector, int index, SqlTypeName typeName, Object value) {
        if (value == null) {
            setNull(vector, index);
            return;
        }
        switch (typeName) {
            case INTEGER -> ((IntVector) vector).setSafe(index, ((Number) value).intValue());
            case BIGINT -> ((BigIntVector) vector).setSafe(index, ((Number) value).longValue());
            case FLOAT -> ((Float4Vector) vector).setSafe(index, ((Number) value).floatValue());
            case DOUBLE -> ((Float8Vector) vector).setSafe(index, ((Number) value).doubleValue());
            case VARCHAR, CHAR -> ((VarCharVector) vector).setSafe(index, value.toString().getBytes(StandardCharsets.UTF_8));
            case BOOLEAN -> ((BitVector) vector).setSafe(index, (Boolean) value ? 1 : 0);
            default -> ((VarCharVector) vector).setSafe(index, value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void setNull(FieldVector vector, int index) {
        if (vector instanceof IntVector v) v.setNull(index);
        else if (vector instanceof BigIntVector v) v.setNull(index);
        else if (vector instanceof Float4Vector v) v.setNull(index);
        else if (vector instanceof Float8Vector v) v.setNull(index);
        else if (vector instanceof VarCharVector v) v.setNull(index);
        else if (vector instanceof BitVector v) v.setNull(index);
    }

    private void extractRows(VectorSchemaRoot root, List<ExampleTable.ColumnDef> columns, List<Object[]> rows) {
        int rowCount = root.getRowCount();
        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            Object[] row = new Object[columns.size()];
            for (int colIdx = 0; colIdx < columns.size(); colIdx++) {
                ExampleTable.ColumnDef col = columns.get(colIdx);
                FieldVector vector = root.getVector(col.name);
                row[colIdx] = getVectorValue(vector, rowIdx, col.typeName);
            }
            rows.add(row);
        }
    }

    private Object getVectorValue(FieldVector vector, int index, SqlTypeName typeName) {
        if (vector.isNull(index)) {
            return null;
        }
        switch (typeName) {
            case INTEGER -> { return ((IntVector) vector).get(index); }
            case BIGINT -> { return ((BigIntVector) vector).get(index); }
            case FLOAT -> { return ((Float4Vector) vector).get(index); }
            case DOUBLE -> { return ((Float8Vector) vector).get(index); }
            case VARCHAR, CHAR -> {
                byte[] bytes = ((VarCharVector) vector).get(index);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            case BOOLEAN -> { return ((BitVector) vector).get(index) == 1; }
            default -> {
                byte[] bytes = ((VarCharVector) vector).get(index);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    public void close() {
        allocator.close();
    }

    public BufferAllocator getAllocator() {
        return allocator;
    }
}

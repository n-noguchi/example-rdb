package com.example.rdb.storage;

import com.example.rdb.schema.ExampleTable;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class ArrowBatchEnumerable extends AbstractEnumerable<Object[]> implements Enumerable<Object[]> {

    private final Path arrowFile;
    private final List<ExampleTable.ColumnDef> columns;
    private final BufferAllocator allocator;

    public ArrowBatchEnumerable(Path arrowFile, List<ExampleTable.ColumnDef> columns, BufferAllocator allocator) {
        this.arrowFile = arrowFile;
        this.columns = columns;
        this.allocator = allocator;
    }

    @Override
    public Enumerator<Object[]> enumerator() {
        try {
            FileInputStream fis = new FileInputStream(arrowFile.toFile());
            SeekableReadChannel channel = new SeekableReadChannel(fis.getChannel());
            ArrowFileReader reader = new ArrowFileReader(channel, allocator);
            return new ArrowBatchEnumerator(reader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Arrow file: " + arrowFile, e);
        }
    }

    private class ArrowBatchEnumerator implements Enumerator<Object[]> {

        private final ArrowFileReader reader;
        private final VectorSchemaRoot root;
        private int currentRowIndex = -1;
        private int currentBatchRowCount = 0;
        private boolean exhausted = false;

        ArrowBatchEnumerator(ArrowFileReader reader) {
            this.reader = reader;
            try {
                this.root = reader.getVectorSchemaRoot();
            } catch (IOException e) {
                throw new RuntimeException("Failed to get VectorSchemaRoot", e);
            }
        }

        @Override
        public Object[] current() {
            Object[] row = new Object[columns.size()];
            for (int colIdx = 0; colIdx < columns.size(); colIdx++) {
                ExampleTable.ColumnDef col = columns.get(colIdx);
                FieldVector vector = root.getVector(col.name);
                row[colIdx] = extractValue(vector, currentRowIndex, col.typeName);
            }
            return row;
        }

        @Override
        public boolean moveNext() {
            currentRowIndex++;
            if (currentRowIndex < currentBatchRowCount) {
                return true;
            }
            return loadNextBatch();
        }

        private boolean loadNextBatch() {
            if (exhausted) return false;
            try {
                boolean loaded = reader.loadNextBatch();
                if (!loaded) {
                    exhausted = true;
                    close();
                    return false;
                }
                currentBatchRowCount = root.getRowCount();
                currentRowIndex = 0;
                return currentBatchRowCount > 0 || loadNextBatch();
            } catch (IOException e) {
                throw new RuntimeException("Failed to load Arrow batch", e);
            }
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("Arrow batch reader does not support reset");
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (IOException e) {
                // ignore close errors
            }
        }

        private Object extractValue(FieldVector vector, int index, SqlTypeName typeName) {
            if (vector.isNull(index)) {
                return null;
            }
            switch (typeName) {
                case INTEGER -> { return ((org.apache.arrow.vector.IntVector) vector).get(index); }
                case BIGINT -> { return ((org.apache.arrow.vector.BigIntVector) vector).get(index); }
                case FLOAT -> { return ((org.apache.arrow.vector.Float4Vector) vector).get(index); }
                case DOUBLE -> { return ((org.apache.arrow.vector.Float8Vector) vector).get(index); }
                case VARCHAR, CHAR -> {
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
}

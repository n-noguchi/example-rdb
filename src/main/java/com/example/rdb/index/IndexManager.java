package com.example.rdb.index;

import com.example.rdb.schema.ExampleTable;
import com.example.rdb.schema.ExampleTable.ColumnDef;
import org.apache.arrow.memory.BufferAllocator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages all secondary indexes for a single table.
 * Handles index lifecycle, DML maintenance, and covering index scans.
 *
 * Architecture per index:
 * - Base: sorted Arrow IPC file (immutable, from checkpoint)
 * - Delta: in-memory CoveringDeltaIndex (post-checkpoint changes)
 * - Tombstones: deleted base row IDs
 */
public class IndexManager {

    private final String tableName;
    private final List<ColumnDef> tableColumns;
    private final BufferAllocator allocator;
    private final Path indexDir;
    private final AtomicLong rowIdGenerator;

    private final Map<String, CoveringIndexState> indexes = new ConcurrentHashMap<>();

    public IndexManager(String tableName, List<ColumnDef> tableColumns,
                        BufferAllocator allocator, Path indexDir, AtomicLong rowIdGenerator) {
        this.tableName = tableName;
        this.tableColumns = tableColumns;
        this.allocator = allocator;
        this.indexDir = indexDir;
        this.rowIdGenerator = rowIdGenerator;
    }

    private static class CoveringIndexState {
        final IndexDefinition definition;
        final CoveringDeltaIndex delta;
        Path baseFilePath;

        CoveringIndexState(IndexDefinition definition) {
            this.definition = definition;
            this.delta = new CoveringDeltaIndex();
        }
    }

    // ── Index lifecycle ──

    public void createIndex(IndexDefinition def, List<Object[]> allRows, List<Long> rowIds) {
        if (indexes.containsKey(def.name())) {
            throw new IllegalArgumentException("Index already exists: " + def.name());
        }
        validateColumns(def);

        CoveringIndexState state = new CoveringIndexState(def);
        if (indexDir != null) {
            state.baseFilePath = indexDir.resolve(def.name() + ".arrow");
        }
        indexes.put(def.name(), state);

        if (indexDir != null && allocator != null && !allRows.isEmpty()) {
            buildBaseIndexFile(def, state, allRows, rowIds);
        }
    }

    /**
     * Register an index definition without building the base file.
     * Used during recovery when the base file already exists from a checkpoint.
     */
    public void registerIndex(IndexDefinition def) {
        if (indexes.containsKey(def.name())) {
            throw new IllegalArgumentException("Index already exists: " + def.name());
        }
        validateColumns(def);

        CoveringIndexState state = new CoveringIndexState(def);
        if (indexDir != null) {
            state.baseFilePath = indexDir.resolve(def.name() + ".arrow");
        }
        indexes.put(def.name(), state);
    }

    public void dropIndex(String indexName) {
        CoveringIndexState state = indexes.remove(indexName);
        if (state != null && state.baseFilePath != null) {
            try {
                java.nio.file.Files.deleteIfExists(state.baseFilePath);
            } catch (IOException ignored) {
            }
        }
    }

    public void dropAll() {
        for (String name : new ArrayList<>(indexes.keySet())) {
            dropIndex(name);
        }
    }

    public List<IndexDefinition> getDefinitions() {
        return indexes.values().stream().map(s -> s.definition).toList();
    }

    public boolean hasIndex(String indexName) {
        return indexes.containsKey(indexName);
    }

    // ── DML maintenance ──

    public void onInsert(long rowId, Object[] row) {
        for (CoveringIndexState state : indexes.values()) {
            CoveringEntry entry = buildEntry(state.definition, rowId, row);
            state.delta.insert(entry);
        }
    }

    public void onDelete(long rowId, Object[] row) {
        for (CoveringIndexState state : indexes.values()) {
            state.delta.deleteByRowId(rowId);
        }
    }

    public void onUpdate(long rowId, Object[] oldRow, Object[] newRow) {
        for (CoveringIndexState state : indexes.values()) {
            CoveringEntry newEntry = buildEntry(state.definition, rowId, newRow);
            state.delta.update(rowId, newEntry);
        }
    }

    // ── Query ──

    /**
     * Find a covering index that can serve the given query.
     * Returns the best matching index or null.
     *
     * Currently supports equality lookup on the leading key column.
     */
    public IndexScanResult scanCovering(String keyColumnName, Object keyValue,
                                         List<String> requiredColumns) {
        for (CoveringIndexState state : indexes.values()) {
            IndexDefinition def = state.definition;

            // Check leading key column match
            if (def.keyColumns().isEmpty()) continue;
            if (!def.keyColumns().get(0).equalsIgnoreCase(keyColumnName)) continue;

            // Check coverage
            if (!def.covers(requiredColumns)) continue;

            // Perform lookup
            IndexKey searchKey = IndexKey.of(keyValue);
            List<CoveringEntry> results = new ArrayList<>();

            // Delta entries - use prefix lookup for composite index support
            results.addAll(state.delta.prefixLookup(searchKey));

            // Base entries (from Arrow file) - filter by prefix match
            if (state.baseFilePath != null && allocator != null) {
                try {
                    CoveringIndexFile fileReader = new CoveringIndexFile(allocator);
                    Set<Long> tombstones = state.delta.getTombstones();
                    List<CoveringEntry> baseResults = fileReader.prefixScan(
                            state.baseFilePath, def, tableColumns, searchKey, tombstones);
                    results.addAll(baseResults);
                } catch (IOException e) {
                    throw new RuntimeException("Index scan failed for " + def.name(), e);
                }
            }

            return new IndexScanResult(def, results, tableColumns);
        }
        return null;
    }

    /**
     * Range scan with optional lower/upper bounds on the leading key column.
     */
    public IndexScanResult scanRange(String keyColumnName, Object lower, boolean lowerInclusive,
                                      Object upper, boolean upperInclusive,
                                      List<String> requiredColumns) {
        for (CoveringIndexState state : indexes.values()) {
            IndexDefinition def = state.definition;
            if (def.keyColumns().isEmpty()) continue;
            if (!def.keyColumns().get(0).equalsIgnoreCase(keyColumnName)) continue;
            if (!def.covers(requiredColumns)) continue;

            IndexKey lowerKey = lower != null ? IndexKey.of(lower) : null;
            IndexKey upperKey = upper != null ? IndexKey.of(upper) : null;

            List<CoveringEntry> results = new ArrayList<>();

            // Delta entries
            results.addAll(state.delta.rangeScan(lowerKey, lowerInclusive, upperKey, upperInclusive));

            // Base entries
            if (state.baseFilePath != null && allocator != null) {
                try {
                    CoveringIndexFile fileReader = new CoveringIndexFile(allocator);
                    Set<Long> tombstones = state.delta.getTombstones();
                    results.addAll(fileReader.rangeScan(
                            state.baseFilePath, def, tableColumns,
                            lowerKey, lowerInclusive, upperKey, upperInclusive, tombstones));
                } catch (IOException e) {
                    throw new RuntimeException("Index range scan failed for " + def.name(), e);
                }
            }

            return new IndexScanResult(def, results, tableColumns);
        }
        return null;
    }

    // ── Checkpoint ──

    public void checkpoint(List<Object[]> allRows, List<Long> rowIds) {
        for (CoveringIndexState state : indexes.values()) {
            if (state.baseFilePath != null && allocator != null) {
                buildBaseIndexFile(state.definition, state, allRows, rowIds);
            }
            state.delta.clear();
        }
    }

    public boolean hasBaseFiles() {
        return indexes.values().stream().anyMatch(s -> s.baseFilePath != null);
    }

    public void loadFromBaseFiles() {
        if (allocator == null) return;
        CoveringIndexFile fileReader = new CoveringIndexFile(allocator);
        for (CoveringIndexState state : indexes.values()) {
            if (state.baseFilePath != null && java.nio.file.Files.exists(state.baseFilePath)) {
                // Base file exists, delta starts empty
                state.delta.clear();
            }
        }
    }

    // ── Internal ──

    private void validateColumns(IndexDefinition def) {
        for (String keyCol : def.keyColumns()) {
            if (findColumnIndex(keyCol) < 0) {
                throw new IllegalArgumentException("Index key column not found: " + keyCol);
            }
        }
        for (String incCol : def.includeColumns()) {
            if (findColumnIndex(incCol) < 0) {
                throw new IllegalArgumentException("Index include column not found: " + incCol);
            }
        }
    }

    private int findColumnIndex(String name) {
        for (int i = 0; i < tableColumns.size(); i++) {
            if (tableColumns.get(i).name.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private CoveringEntry buildEntry(IndexDefinition def, long rowId, Object[] row) {
        List<String> allCols = def.allCoveredColumns();
        Object[] values = new Object[allCols.size()];
        for (int i = 0; i < allCols.size(); i++) {
            int colIdx = findColumnIndex(allCols.get(i));
            values[i] = colIdx >= 0 && colIdx < row.length ? row[colIdx] : null;
        }

        Object[] keyValues = new Object[def.keyColumns().size()];
        for (int i = 0; i < def.keyColumns().size(); i++) {
            keyValues[i] = values[i];
        }

        return new CoveringEntry(rowId, new IndexKey(keyValues), values);
    }

    private void buildBaseIndexFile(IndexDefinition def, CoveringIndexState state,
                                     List<Object[]> allRows, List<Long> rowIds) {
        List<CoveringEntry> entries = new ArrayList<>();
        for (int i = 0; i < allRows.size(); i++) {
            long rowId = rowIds.get(i);
            entries.add(buildEntry(def, rowId, allRows.get(i)));
        }
        entries.sort(Comparator.naturalOrder());

        try {
            CoveringIndexFile fileWriter = new CoveringIndexFile(allocator);
            fileWriter.write(state.baseFilePath, def, tableColumns, entries);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build index file: " + def.name(), e);
        }
    }

    // ── Result ──

    public static class IndexScanResult {
        public final IndexDefinition indexDefinition;
        public final List<CoveringEntry> entries;
        public final List<ColumnDef> tableColumns;

        public IndexScanResult(IndexDefinition indexDefinition, List<CoveringEntry> entries,
                               List<ColumnDef> tableColumns) {
            this.indexDefinition = indexDefinition;
            this.entries = entries;
            this.tableColumns = tableColumns;
        }

        /**
         * Convert entries to Object[] rows containing only the requested columns.
         */
        public List<Object[]> toRows(List<String> requestedColumns) {
            List<String> coveredCols = indexDefinition.allCoveredColumns();
            List<Object[]> result = new ArrayList<>();

            for (CoveringEntry entry : entries) {
                Object[] row = new Object[requestedColumns.size()];
                for (int i = 0; i < requestedColumns.size(); i++) {
                    int coveredIdx = findColumnIndexInList(coveredCols, requestedColumns.get(i));
                    row[i] = coveredIdx >= 0 ? entry.getAllValues()[coveredIdx] : null;
                }
                result.add(row);
            }
            return result;
        }

        private static int findColumnIndexInList(List<String> cols, String name) {
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).equalsIgnoreCase(name)) return i;
            }
            return -1;
        }
    }
}

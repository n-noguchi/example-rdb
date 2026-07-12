package com.example.rdb;

import com.example.rdb.engine.TransactionManager;
import com.example.rdb.jdbc.JdbcDdlSupport;
import com.example.rdb.schema.CatalogManager;
import com.example.rdb.schema.ExampleSchema;
import com.example.rdb.schema.ExampleTable;
import com.example.rdb.storage.ArrowStorage;
import com.example.rdb.wal.CheckpointManager;
import com.example.rdb.wal.WalManager;
import com.example.rdb.wal.WalOperation;
import com.example.rdb.wal.WalRecord;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.type.SqlTypeName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class ExampleRdb {

    private final Path dataDir;
    private final ExampleSchema schema;
    private final WalManager walManager;
    private final TransactionManager transactionManager;
    private final ArrowStorage storage;
    private final CatalogManager catalogManager;
    private CheckpointManager checkpointManager;

    public ExampleRdb() {
        this(null);
    }

    public ExampleRdb(Path dataDir) {
        this.dataDir = dataDir;
        this.schema = new ExampleSchema();

        if (dataDir != null) {
            try {
                Files.createDirectories(dataDir);
                Path walDir = dataDir.resolve("wal");
                Path tablesDir = dataDir.resolve("tables");
                Path metaDir = dataDir.resolve("meta");
                Files.createDirectories(tablesDir);
                Files.createDirectories(metaDir);

                this.walManager = new WalManager(walDir);
                this.transactionManager = new TransactionManager(walManager);
                this.storage = new ArrowStorage();
                this.catalogManager = new CatalogManager(metaDir.resolve("catalog.json"));

                recover();
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize storage", e);
            }
        } else {
            this.walManager = null;
            this.transactionManager = null;
            this.storage = null;
            this.catalogManager = null;
        }
    }

    private void recover() throws IOException {
        if (catalogManager == null) return;

        List<CatalogManager.TableDef> tableDefs = catalogManager.load();
        for (CatalogManager.TableDef def : tableDefs) {
            List<ExampleTable.ColumnDef> columns = def.columns.stream()
                    .map(c -> new ExampleTable.ColumnDef(c.name, c.typeName))
                    .toList();
            ExampleTable table = new ExampleTable(def.name, columns, def.primaryKeyColumns);
            configureTable(table);
            schema.addTable(table);

            Path arrowFile = dataDir.resolve("tables").resolve(def.name + ".arrow");
            if (Files.exists(arrowFile)) {
                table.setBaseDataPath(arrowFile);
            }

            // Restore indexes
            if (!def.indexes.isEmpty() && storage != null) {
                Path indexDir = dataDir.resolve("indexes").resolve(def.name);
                com.example.rdb.index.IndexManager idxMgr = new com.example.rdb.index.IndexManager(
                        def.name, table.getColumns(), storage.getAllocator(),
                        indexDir, table.getRowIdGenerator());
                table.setIndexManager(idxMgr);

                for (CatalogManager.IndexDef idxDef : def.indexes) {
                    com.example.rdb.index.IndexDefinition indexDef = new com.example.rdb.index.IndexDefinition(
                            idxDef.name, def.name, idxDef.keyColumns, idxDef.includeColumns, false);
                    idxMgr.registerIndex(indexDef);
                }
            }
        }

        applyWalRecords();
    }

    private void configureTable(ExampleTable table) {
        if (transactionManager != null) {
            table.setWalAware(transactionManager);
        }
        if (storage != null) {
            table.setAllocator(storage.getAllocator());
        }
    }

    private void applyWalRecords() throws IOException {
        List<WalRecord> records = walManager.readAllSegments();

        // First pass: identify committed transactions
        Set<Integer> committedTxIds = new HashSet<>();
        for (WalRecord record : records) {
            if (record.getOperation() == WalOperation.COMMIT) {
                committedTxIds.add(record.getTxId());
            }
        }

        // Second pass: apply only records from committed transactions
        for (WalRecord record : records) {
            if (record.getOperation() != WalOperation.INSERT && record.getOperation() != WalOperation.DELETE) {
                continue;
            }
            if (!committedTxIds.contains(record.getTxId())) {
                continue;
            }

            ExampleTable table = schema.getExampleTable(record.getTableName());
            if (table == null || record.getValues() == null) continue;

            Object[] row = new Object[table.getColumns().size()];
            for (int i = 0; i < table.getColumns().size(); i++) {
                String colName = table.getColumns().get(i).name;
                Object value = record.getValues().get(colName);
                row[i] = normalizeValue(value, table.getColumns().get(i).typeName);
            }

            if (record.getOperation() == WalOperation.INSERT) {
                table.addRow(row);
            } else if (record.getOperation() == WalOperation.DELETE) {
                table.deleteRow(row);
            }
        }
    }

    private Object normalizeValue(Object value, SqlTypeName typeName) {
        if (value == null) return null;
        if (value instanceof Number num) {
            switch (typeName) {
                case INTEGER -> { return num.intValue(); }
                case BIGINT -> { return num.longValue(); }
                case FLOAT -> { return num.floatValue(); }
                case DOUBLE -> { return num.doubleValue(); }
            }
        }
        return value;
    }

    public void createTable(String tableName, ExampleTable.ColumnDef... columns) {
        createTable(tableName, Arrays.asList(columns), List.of());
    }

    public void createTable(String tableName, List<ExampleTable.ColumnDef> columns, List<String> primaryKeyColumns) {
        ExampleTable table = new ExampleTable(tableName, columns, primaryKeyColumns);
        configureTable(table);
        schema.addTable(table);
        persistCatalog();
    }

    public boolean dropTable(String tableName) {
        ExampleTable table = schema.getExampleTable(tableName);
        if (table == null) return false;

        try {
            if (table.getIndexManager() != null) {
                table.getIndexManager().dropAll();
            }
            if (dataDir != null) {
                Files.deleteIfExists(dataDir.resolve("tables").resolve(tableName + ".arrow"));
            }
            schema.removeTable(tableName);
            persistCatalog();
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove table data for " + tableName, e);
        }
    }

    private void persistCatalog() {
        if (catalogManager != null) {
            try {
                catalogManager.save(schema.getTables().values());
            } catch (IOException e) {
                throw new RuntimeException("Failed to persist catalog", e);
            }
        }
    }

    public void attachWalAware(ExampleTable table) {
        if (transactionManager != null) {
            table.setWalAware(transactionManager);
        }
    }

    public void insert(String tableName, Object... values) {
        ExampleTable table = schema.getExampleTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        table.addRow(values);
    }

    public void createIndex(String tableName, String indexName,
                            List<String> keyColumns, List<String> includeColumns) {
        ExampleTable table = schema.getExampleTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }

        if (table.getIndexManager() == null) {
            Path indexDir = dataDir != null ? dataDir.resolve("indexes").resolve(tableName) : null;
            table.setIndexManager(new com.example.rdb.index.IndexManager(
                    tableName, table.getColumns(),
                    storage != null ? storage.getAllocator() : null,
                    indexDir, table.getRowIdGenerator()));
        }

        com.example.rdb.index.IndexDefinition def = new com.example.rdb.index.IndexDefinition(
                indexName, tableName, keyColumns, includeColumns, false);

        // Get snapshot of all rows for index construction
        List<Object[]> snapshotRows = getSnapshotRows(table);
        List<Long> snapshotRowIds = getSnapshotRowIds(table);

        table.getIndexManager().createIndex(def, snapshotRows, snapshotRowIds);
        persistCatalog();
    }

    public boolean dropIndex(String tableName, String indexName) {
        ExampleTable table = schema.getExampleTable(tableName);
        if (table == null || table.getIndexManager() == null) return false;
        if (!table.getIndexManager().hasIndex(indexName)) return false;

        table.getIndexManager().dropIndex(indexName);
        persistCatalog();
        return true;
    }

    private List<Object[]> getSnapshotRows(ExampleTable table) {
        List<Object[]> rows = new java.util.ArrayList<>();
        if (storage != null && dataDir != null && table.getBaseDataPath() != null) {
            try {
                rows.addAll(storage.readTable(table.getBaseDataPath(), table.getColumns()));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read base data for snapshot", e);
            }
        }
        rows.addAll(table.getDeltaRows());
        return rows;
    }

    private List<Long> getSnapshotRowIds(ExampleTable table) {
        List<Long> ids = new java.util.ArrayList<>();
        if (storage != null && dataDir != null && table.getBaseDataPath() != null) {
            try {
                int baseCount = storage.readTable(table.getBaseDataPath(), table.getColumns()).size();
                for (int i = 0; i < baseCount; i++) {
                    ids.add((long) i);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read base data for row IDs", e);
            }
        }
        ids.addAll(table.getDeltaRowIds());
        return ids;
    }

    public Connection getConnection() throws SQLException {
        Properties info = new Properties();
        info.setProperty("lex", "MYSQL");
        Connection connection = DriverManager.getConnection("jdbc:calcite:", info);
        CalciteConnection calciteConnection = connection.unwrap(CalciteConnection.class);
        SchemaPlus rootSchema = calciteConnection.getRootSchema();
        rootSchema.add("rdb", schema);
        calciteConnection.setSchema("rdb");
        return JdbcDdlSupport.wrap(connection, this);
    }

    public void startCheckpoint(long intervalSeconds) {
        if (storage != null && walManager != null && dataDir != null) {
            checkpointManager = new CheckpointManager(
                    storage, schema, walManager, dataDir.resolve("tables"), intervalSeconds);
            checkpointManager.start();
        }
    }

    public void checkpoint() throws IOException {
        if (checkpointManager != null) {
            checkpointManager.checkpoint();
        } else if (storage != null && dataDir != null) {
            Path tablesDir = dataDir.resolve("tables");
            for (Map.Entry<String, ExampleTable> entry : schema.getTables().entrySet()) {
                ExampleTable table = entry.getValue();
                Path arrowFile = tablesDir.resolve(entry.getKey() + ".arrow");
                storage.writeMergedTable(arrowFile, table.getBaseDataPath(),
                        table.getDeltaRows(), table.getColumns());
                table.setBaseDataPath(arrowFile);
                table.clearDelta();
            }
            walManager.rotateSegment();
            walManager.deleteOldSegments(walManager.getCurrentSegment());
        }
    }

    public ExampleSchema getSchema() {
        return schema;
    }

    public WalManager getWalManager() {
        return walManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public ArrowStorage getStorage() {
        return storage;
    }

    public void close() throws IOException {
        if (checkpointManager != null) {
            checkpointManager.stop();
        }
        if (walManager != null) {
            walManager.close();
        }
        if (storage != null) {
            storage.close();
        }
    }
}

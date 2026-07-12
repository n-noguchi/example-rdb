package com.example.rdb;

import com.example.rdb.schema.ExampleTable;
import com.example.rdb.wal.WalManager;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for transaction Atomicity (the 'A' in ACID).
 *
 * Verifies that:
 * - Committed transactions are fully recovered after restart
 * - Uncommitted transactions (crash before COMMIT) are NOT recovered
 * - Aborted transactions are NOT recovered
 * - Multi-row transactions are all-or-nothing
 * - Mixed committed/uncommitted scenarios are handled correctly
 */
class TransactionAtomicityTest {

    @TempDir
    Path tempDir;

    private ExampleTable.ColumnDef col(String name, SqlTypeName type) {
        return new ExampleTable.ColumnDef(name, type);
    }

    private void assertRowCount(ExampleRdb rdb, String table, int expected) throws Exception {
        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM " + table)) {
            assertThat(rs.next()).isTrue();
            assertThat(((Number) rs.getObject("cnt")).intValue())
                    .as("row count in table " + table)
                    .isEqualTo(expected);
        }
    }

    private boolean rowExists(ExampleRdb rdb, String table, String whereCol, Object whereVal) throws Exception {
        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT 1 FROM " + table + " WHERE " + whereCol + " = " + formatLiteral(whereVal))) {
            return rs.next();
        }
    }

    private String formatLiteral(Object val) {
        if (val == null) return "NULL";
        if (val instanceof Number) return val.toString();
        if (val instanceof Boolean) return val.toString();
        return "'" + val + "'";
    }

    private Map<String, Object> row(int id, String name) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        return map;
    }

    /**
     * Write WAL records directly, bypassing ExampleRdb's memory layer.
     * Simulates a crash where WAL was written but the process died before updating memory.
     */
    private void writeWalRecords(Path dataDir, WalScenario scenario) throws Exception {
        WalManager wal = new WalManager(dataDir.resolve("wal"));
        scenario.write(wal);
        wal.close();
    }

    @FunctionalInterface
    private interface WalScenario {
        void write(WalManager wal) throws Exception;
    }

    // ──────────────────────────────────────────────────────────────
    // Recovery behavior: committed transactions
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Committed transactions are recovered")
    class CommittedRecovery {

        @Test
        @DisplayName("Committed single INSERT is present after recovery")
        void committedInsertRecovered() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            // Write committed transaction to WAL
            writeWalRecords(tempDir, wal -> {
                int txId = wal.beginTransaction();
                wal.logInsert(txId, "items", row(1, "committed"));
                wal.commitTransaction(txId);
            });

            // Recover
            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 1);
            assertThat(rowExists(rdb2, "items", "id", 1)).isTrue();
            rdb2.close();
        }

        @Test
        @DisplayName("Committed multi-row INSERT is fully recovered (all-or-nothing)")
        void committedMultiRowRecovered() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            writeWalRecords(tempDir, wal -> {
                int txId = wal.beginTransaction();
                wal.logInsert(txId, "items", row(1, "first"));
                wal.logInsert(txId, "items", row(2, "second"));
                wal.logInsert(txId, "items", row(3, "third"));
                wal.commitTransaction(txId);
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 3);
            assertThat(rowExists(rdb2, "items", "id", 1)).isTrue();
            assertThat(rowExists(rdb2, "items", "id", 2)).isTrue();
            assertThat(rowExists(rdb2, "items", "id", 3)).isTrue();
            rdb2.close();
        }

        @Test
        @DisplayName("Multiple committed transactions are all recovered")
        void multipleCommittedRecovered() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            writeWalRecords(tempDir, wal -> {
                int tx1 = wal.beginTransaction();
                wal.logInsert(tx1, "items", row(1, "a"));
                wal.commitTransaction(tx1);

                int tx2 = wal.beginTransaction();
                wal.logInsert(tx2, "items", row(2, "b"));
                wal.commitTransaction(tx2);

                int tx3 = wal.beginTransaction();
                wal.logInsert(tx3, "items", row(3, "c"));
                wal.commitTransaction(tx3);
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 3);
            rdb2.close();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Recovery behavior: uncommitted transactions
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Uncommitted transactions are NOT recovered")
    class UncommittedRecovery {

        @Test
        @DisplayName("INSERT without COMMIT is NOT recovered (crash before commit)")
        void uncommittedInsertNotRecovered() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            // Write BEGIN + INSERT but NO COMMIT (simulates crash before commit)
            writeWalRecords(tempDir, wal -> {
                int txId = wal.beginTransaction();
                wal.logInsert(txId, "items", row(1, "uncommitted"));
                // No commit - crash happened here
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 0);
            assertThat(rowExists(rdb2, "items", "id", 1)).isFalse();
            rdb2.close();
        }

        @Test
        @DisplayName("ABORTed transaction is NOT recovered")
        void abortedInsertNotRecovered() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            writeWalRecords(tempDir, wal -> {
                int txId = wal.beginTransaction();
                wal.logInsert(txId, "items", row(1, "aborted"));
                wal.abortTransaction(txId);
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 0);
            rdb2.close();
        }

        @Test
        @DisplayName("Multi-row INSERT without COMMIT: none recovered (atomic)")
        void multiRowUncommittedNoneRecovered() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            writeWalRecords(tempDir, wal -> {
                int txId = wal.beginTransaction();
                wal.logInsert(txId, "items", row(1, "first"));
                wal.logInsert(txId, "items", row(2, "second"));
                wal.logInsert(txId, "items", row(3, "third"));
                // No COMMIT - crash happened after 3 INSERTs but before commit
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 0);
            assertThat(rowExists(rdb2, "items", "id", 1)).isFalse();
            assertThat(rowExists(rdb2, "items", "id", 2)).isFalse();
            assertThat(rowExists(rdb2, "items", "id", 3)).isFalse();
            rdb2.close();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Mixed scenarios
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mixed committed and uncommitted transactions")
    class MixedScenarios {

        @Test
        @DisplayName("Committed tx1 survives, uncommitted tx2 is discarded")
        void committedAndUncommitted() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            writeWalRecords(tempDir, wal -> {
                // tx1: committed
                int tx1 = wal.beginTransaction();
                wal.logInsert(tx1, "items", row(1, "committed"));
                wal.commitTransaction(tx1);

                // tx2: uncommitted (crash before commit)
                int tx2 = wal.beginTransaction();
                wal.logInsert(tx2, "items", row(2, "uncommitted"));
                // No COMMIT
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 1);
            assertThat(rowExists(rdb2, "items", "id", 1)).isTrue();
            assertThat(rowExists(rdb2, "items", "id", 2)).isFalse();
            rdb2.close();
        }

        @Test
        @DisplayName("Committed tx1, ABORTed tx2, committed tx3: only 1 and 3 recovered")
        void committedAbortedCommitted() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            writeWalRecords(tempDir, wal -> {
                int tx1 = wal.beginTransaction();
                wal.logInsert(tx1, "items", row(1, "committed-1"));
                wal.commitTransaction(tx1);

                int tx2 = wal.beginTransaction();
                wal.logInsert(tx2, "items", row(2, "aborted"));
                wal.abortTransaction(tx2);

                int tx3 = wal.beginTransaction();
                wal.logInsert(tx3, "items", row(3, "committed-3"));
                wal.commitTransaction(tx3);
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 2);
            assertThat(rowExists(rdb2, "items", "id", 1)).isTrue();
            assertThat(rowExists(rdb2, "items", "id", 2)).isFalse();
            assertThat(rowExists(rdb2, "items", "id", 3)).isTrue();
            rdb2.close();
        }

        @Test
        @DisplayName("Interleaved transactions: tx1 commits, tx2 uncommitted")
        void interleavedTransactions() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));
            rdb1.close();

            writeWalRecords(tempDir, wal -> {
                // tx1 begins
                int tx1 = wal.beginTransaction();
                // tx2 begins (interleaved)
                int tx2 = wal.beginTransaction();
                // tx2 inserts (uncommitted)
                wal.logInsert(tx2, "items", row(2, "uncommitted"));
                // tx1 inserts and commits
                wal.logInsert(tx1, "items", row(1, "committed"));
                wal.commitTransaction(tx1);
                // tx2 never commits (crash)
            });

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 1);
            assertThat(rowExists(rdb2, "items", "id", 1)).isTrue();
            assertThat(rowExists(rdb2, "items", "id", 2)).isFalse();
            rdb2.close();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // WAL write-ahead guarantee verification
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WAL records are present before memory is updated")
    class WriteAheadGuarantee {

        @Test
        @DisplayName("WAL contains BEGIN/INSERT/COMMIT after successful INSERT via JDBC")
        void walRecordsAfterInsert() throws Exception {
            ExampleRdb rdb = new ExampleRdb(tempDir);
            rdb.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));

            try (Connection conn = rdb.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO items VALUES (1, 'test')");
            }

            // Verify WAL has all three records
            var records = rdb.getWalManager().readAllRecords();
            assertThat(records).hasSize(3);
            assertThat(records.get(0).getOperation()).isEqualTo(com.example.rdb.wal.WalOperation.BEGIN);
            assertThat(records.get(1).getOperation()).isEqualTo(com.example.rdb.wal.WalOperation.INSERT);
            assertThat(records.get(2).getOperation()).isEqualTo(com.example.rdb.wal.WalOperation.COMMIT);

            rdb.close();
        }

        @Test
        @DisplayName("DELETE via JDBC writes BEGIN/DELETE/COMMIT to WAL")
        void walRecordsAfterDelete() throws Exception {
            ExampleRdb rdb = new ExampleRdb(tempDir);
            rdb.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));

            try (Connection conn = rdb.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO items VALUES (1, 'test')");
                stmt.executeUpdate("DELETE FROM items WHERE id = 1");
            }

            var records = rdb.getWalManager().readAllRecords();
            // INSERT: BEGIN+INSERT+COMMIT, DELETE: BEGIN+DELETE+COMMIT = 6 total
            assertThat(records).hasSize(6);

            var deleteRecords = records.stream()
                    .filter(r -> r.getOperation() == com.example.rdb.wal.WalOperation.DELETE)
                    .toList();
            assertThat(deleteRecords).hasSize(1);
            assertThat(deleteRecords.get(0).getTableName()).isEqualTo("items");

            rdb.close();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Checkpoint + uncommitted interaction
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Checkpoint and uncommitted transaction interaction")
    class CheckpointInteraction {

        @Test
        @DisplayName("Uncommitted transaction after checkpoint is NOT recovered")
        void uncommittedAfterCheckpoint() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));

            try (Connection conn = rdb1.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO items VALUES (1, 'checkpointed')");
            }
            rdb1.checkpoint();
            rdb1.close();

            // Write uncommitted transaction to WAL after checkpoint
            writeWalRecords(tempDir, wal -> {
                int txId = wal.beginTransaction();
                wal.logInsert(txId, "items", row(2, "uncommitted"));
                // No COMMIT
            });

            // Recover: should have row 1 from checkpoint, NOT row 2
            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 1);
            assertThat(rowExists(rdb2, "items", "id", 1)).isTrue();
            assertThat(rowExists(rdb2, "items", "id", 2)).isFalse();
            rdb2.close();
        }

        @Test
        @DisplayName("Committed transaction after checkpoint IS recovered")
        void committedAfterCheckpoint() throws Exception {
            ExampleRdb rdb1 = new ExampleRdb(tempDir);
            rdb1.createTable("items", col("id", SqlTypeName.INTEGER), col("name", SqlTypeName.VARCHAR));

            try (Connection conn = rdb1.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO items VALUES (1, 'checkpointed')");
            }
            rdb1.checkpoint();
            rdb1.close();

            // Write committed transaction to WAL after checkpoint
            writeWalRecords(tempDir, wal -> {
                int txId = wal.beginTransaction();
                wal.logInsert(txId, "items", row(2, "post-checkpoint"));
                wal.commitTransaction(txId);
            });

            // Recover: should have both rows
            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            assertRowCount(rdb2, "items", 2);
            assertThat(rowExists(rdb2, "items", "id", 1)).isTrue();
            assertThat(rowExists(rdb2, "items", "id", 2)).isTrue();
            rdb2.close();
        }
    }
}

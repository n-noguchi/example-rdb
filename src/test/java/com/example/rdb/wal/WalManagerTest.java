package com.example.rdb.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalManagerTest {

    @TempDir
    Path tempDir;

    private WalManager walManager;

    @BeforeEach
    void setUp() throws Exception {
        walManager = new WalManager(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        walManager.close();
    }

    @Test
    void writeAndReadInsert() throws Exception {
        int txId = walManager.beginTransaction();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 1);
        values.put("name", "Alice");
        walManager.logInsert(txId, "users", values);
        walManager.commitTransaction(txId);

        List<WalRecord> records = walManager.readAllRecords();
        assertThat(records).hasSize(3);
        assertThat(records.get(0).getOperation()).isEqualTo(WalOperation.BEGIN);
        assertThat(records.get(1).getOperation()).isEqualTo(WalOperation.INSERT);
        assertThat(records.get(1).getTableName()).isEqualTo("users");
        assertThat(records.get(1).getValue("id")).isEqualTo(1L);
        assertThat(records.get(1).getValue("name")).isEqualTo("Alice");
        assertThat(records.get(2).getOperation()).isEqualTo(WalOperation.COMMIT);
    }

    @Test
    void multipleTransactions() throws Exception {
        int tx1 = walManager.beginTransaction();
        Map<String, Object> v1 = new LinkedHashMap<>();
        v1.put("id", 1);
        walManager.logInsert(tx1, "users", v1);
        walManager.commitTransaction(tx1);

        int tx2 = walManager.beginTransaction();
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("id", 2);
        walManager.logInsert(tx2, "users", v2);
        walManager.commitTransaction(tx2);

        List<WalRecord> records = walManager.readAllRecords();
        assertThat(records).hasSize(6);
        assertThat(records.get(0).getTxId()).isEqualTo(tx1);
        assertThat(records.get(3).getTxId()).isEqualTo(tx2);
    }

    @Test
    void lsnIsMonotonicallyIncreasing() throws Exception {
        int txId = walManager.beginTransaction();
        walManager.commitTransaction(txId);

        List<WalRecord> records = walManager.readAllRecords();
        for (int i = 1; i < records.size(); i++) {
            assertThat(records.get(i).getLsn()).isGreaterThan(records.get(i - 1).getLsn());
        }
    }

    @Test
    void segmentRotation() throws Exception {
        int seg1 = walManager.getCurrentSegment();

        int txId = walManager.beginTransaction();
        walManager.commitTransaction(txId);

        walManager.rotateSegment();

        int seg2 = walManager.getCurrentSegment();
        assertThat(seg2).isGreaterThan(seg1);

        List<WalRecord> allRecords = walManager.readAllSegments();
        assertThat(allRecords).isNotEmpty();
    }

    @Test
    void deleteOldSegments() throws Exception {
        int txId = walManager.beginTransaction();
        walManager.commitTransaction(txId);
        walManager.rotateSegment();

        walManager.deleteOldSegments(walManager.getCurrentSegment());

        List<WalRecord> remaining = walManager.readAllSegments();
        assertThat(remaining).isEmpty();
    }
}

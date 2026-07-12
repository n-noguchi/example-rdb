package com.example.rdb;

import com.example.rdb.schema.ExampleTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for application-level optimistic locking using a version column.
 *
 * Pattern:
 *   1. SELECT row (read current version)
 *   2. UPDATE ... SET version=version+1 WHERE id=X AND version=<read_version>
 *   3. If affected rows = 0, another writer changed the row first (conflict)
 */
class OptimisticLockTest {

    @TempDir
    Path tempDir;

    private ExampleRdb rdb;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        rdb = new ExampleRdb(tempDir);
        rdb.createTable("account",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("balance", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("txn_id", SqlTypeName.INTEGER));
        connection = rdb.getConnection();

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO account VALUES (1, 1000, 1)");
            stmt.executeUpdate("INSERT INTO account VALUES (2, 2000, 1)");
            stmt.executeUpdate("INSERT INTO account VALUES (3, 3000, 1)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        rdb.close();
    }

    private int getTxnId(int id) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT txn_id FROM account WHERE id = " + id)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt("txn_id");
        }
    }

    private int getBalance(int id) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT balance FROM account WHERE id = " + id)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt("balance");
        }
    }

    @Nested
    @DisplayName("正常系: 競合なし")
    class NoConflict {

        @Test
        @DisplayName("バージョン一致 → UPDATE成功、txn_idインクリメント")
        void updateSucceedsWhenVersionMatches() throws Exception {
            int versionBefore = getTxnId(1);
            assertThat(versionBefore).isEqualTo(1);

            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate(
                        "UPDATE account SET balance = 1100, txn_id = 2 WHERE id = 1 AND txn_id = 1");
                assertThat(affected).isEqualTo(1);
            }

            assertThat(getTxnId(1)).isEqualTo(2);
            assertThat(getBalance(1)).isEqualTo(1100);
        }

        @Test
        @DisplayName("連続更新: version 1→2→3 と順次更新")
        void sequentialUpdates() throws Exception {
            for (int expectedVersion = 1; expectedVersion <= 3; expectedVersion++) {
                int newBalance = 1000 + expectedVersion * 100;
                try (Statement stmt = connection.createStatement()) {
                    int affected = stmt.executeUpdate(String.format(
                            "UPDATE account SET balance = %d, txn_id = %d WHERE id = 1 AND txn_id = %d",
                            newBalance, expectedVersion + 1, expectedVersion));
                    assertThat(affected).as("update at version " + expectedVersion).isEqualTo(1);
                }
            }
            assertThat(getTxnId(1)).isEqualTo(4);
            assertThat(getBalance(1)).isEqualTo(1300);
        }
    }

    @Nested
    @DisplayName("競合検出: バージョン不一致")
    class ConflictDetected {

        @Test
        @DisplayName("先に別のUPDATEがversionを変更 → 古いversion指定のUPDATEは0件")
        void staleVersionReturnsZeroRows() throws Exception {
            // 別の更新が先に実行された（txn_id 1→2）
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE account SET balance = 999, txn_id = 2 WHERE id = 1");
            }
            assertThat(getTxnId(1)).isEqualTo(2);

            // 古いversion(1)で更新を試行 → 競合検出
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate(
                        "UPDATE account SET balance = 1100, txn_id = 2 WHERE id = 1 AND txn_id = 1");
                assertThat(affected)
                        .as("stale version update should affect 0 rows")
                        .isEqualTo(0);
            }

            // データは変更されていない
            assertThat(getBalance(1)).isEqualTo(999);
            assertThat(getTxnId(1)).isEqualTo(2);
        }

        @Test
        @DisplayName("2つの更新が同じversionを読んだ後、最初だけ成功、2番目は空振り")
        void twoWritersFirstWins() throws Exception {
            int baseVersion = getTxnId(2); // = 1

            // Writer A: 先に更新成功
            try (Statement stmt = connection.createStatement()) {
                int affectedA = stmt.executeUpdate(String.format(
                        "UPDATE account SET balance = 2500, txn_id = %d WHERE id = 2 AND txn_id = %d",
                        baseVersion + 1, baseVersion));
                assertThat(affectedA).isEqualTo(1);
            }

            // Writer B: 同じversionで更新 → 空振り
            try (Statement stmt = connection.createStatement()) {
                int affectedB = stmt.executeUpdate(String.format(
                        "UPDATE account SET balance = 2900, txn_id = %d WHERE id = 2 AND txn_id = %d",
                        baseVersion + 1, baseVersion));
                assertThat(affectedB)
                        .as("second writer with stale version should get 0 rows")
                        .isEqualTo(0);
            }

            // Writer Aの結果が残っている
            assertThat(getBalance(2)).isEqualTo(2500);
            assertThat(getTxnId(2)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("リトライ: 競合検出後に再読み込みして再実行")
    class RetryAfterConflict {

        @Test
        @DisplayName("競合 → 再読み込み → リトライ成功")
        void conflictThenRetrySucceeds() throws Exception {
            // 初期状態: balance=3000, txn_id=1
            int rowId = 3;

            // 第一回更新（古いversionで失敗をシミュレート: 別の更新が先にあった）
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE account SET balance = 3500, txn_id = 2 WHERE id = " + rowId);
            }

            // アプリケーション: 古いversion(1)で更新 → 空振り
            int affected;
            try (Statement stmt = connection.createStatement()) {
                affected = stmt.executeUpdate(
                        "UPDATE account SET balance = 4000, txn_id = 2 WHERE id = " + rowId + " AND txn_id = 1");
            }
            assertThat(affected).isEqualTo(0);

            // リトライ: 再読み込みして新しいversionで更新
            int currentVersion = getTxnId(rowId); // = 2
            try (Statement stmt = connection.createStatement()) {
                affected = stmt.executeUpdate(String.format(
                        "UPDATE account SET balance = 4000, txn_id = %d WHERE id = %d AND txn_id = %d",
                        currentVersion + 1, rowId, currentVersion));
                assertThat(affected).isEqualTo(1);
            }

            assertThat(getBalance(rowId)).isEqualTo(4000);
            assertThat(getTxnId(rowId)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("複数行の楽観ロック")
    class MultiRowOptimisticLock {

        @Test
        @DisplayName("全行の一括更新: 各行のversion条件が一致すれば成功")
        void bulkUpdateAllMatch() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE account SET balance = 9999, txn_id = 2 WHERE txn_id = 1");
            }

            for (int id = 1; id <= 3; id++) {
                assertThat(getTxnId(id)).isEqualTo(2);
                assertThat(getBalance(id)).isEqualTo(9999);
            }
        }

        @Test
        @DisplayName("一部行だけversion不一致: 一致する行のみ更新される")
        void partialMatch() throws Exception {
            // id=2のversionだけ先に更新
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE account SET txn_id = 2 WHERE id = 2");
            }

            // txn_id=1の行だけ更新（id=1とid=3）
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate(
                        "UPDATE account SET balance = 5555, txn_id = 2 WHERE txn_id = 1");
                assertThat(affected).isEqualTo(2);
            }

            // id=1とid=3は更新されている
            assertThat(getTxnId(1)).isEqualTo(2);
            assertThat(getBalance(1)).isEqualTo(5555);
            assertThat(getTxnId(3)).isEqualTo(2);
            assertThat(getBalance(3)).isEqualTo(5555);

            // id=2は元のまま（すでにtxn_id=2だったため対象外）
            assertThat(getTxnId(2)).isEqualTo(2);
            assertThat(getBalance(2)).isEqualTo(2000);
        }
    }

    @Test
    @DisplayName("永続化後も楽観ロックが機能する")
    void optimisticLockAfterCheckpoint() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE account SET balance = 1200, txn_id = 2 WHERE id = 1 AND txn_id = 1");
        }
        connection.close();
        rdb.checkpoint();
        rdb.close();

        ExampleRdb rdb2 = new ExampleRdb(tempDir);
        try (Connection conn = rdb2.getConnection();
             Statement stmt = conn.createStatement()) {

            // checkpoint後: id=1 の txn_id=2
            // 古いversion(1)で更新 → 空振り
            int affected = stmt.executeUpdate(
                    "UPDATE account SET balance = 9999, txn_id = 3 WHERE id = 1 AND txn_id = 1");
            assertThat(affected).isEqualTo(0);

            // 正しいversion(2)で更新 → 成功
            affected = stmt.executeUpdate(
                    "UPDATE account SET balance = 9999, txn_id = 3 WHERE id = 1 AND txn_id = 2");
            assertThat(affected).isEqualTo(1);

            try (ResultSet rs = stmt.executeQuery("SELECT balance, txn_id FROM account WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("balance")).isEqualTo(9999);
                assertThat(rs.getInt("txn_id")).isEqualTo(3);
            }
        }
        rdb2.close();
    }
}

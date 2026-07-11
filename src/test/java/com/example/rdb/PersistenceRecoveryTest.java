package com.example.rdb;

import com.example.rdb.schema.ExampleTable;
import com.example.rdb.wal.WalOperation;
import com.example.rdb.wal.WalRecord;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistAndRecover() throws Exception {
        String tableName = "users";
        ExampleTable.ColumnDef[] columns = {
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR),
                new ExampleTable.ColumnDef("age", SqlTypeName.INTEGER)
        };

        ExampleRdb rdb1 = new ExampleRdb(tempDir);
        rdb1.createTable(tableName, columns);

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 30)");
            stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob', 25)");
            stmt.executeUpdate("INSERT INTO users VALUES (3, 'Charlie', 35)");
        }

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("cnt")).isEqualTo(3);
        }

        rdb1.checkpoint();
        rdb1.close();

        ExampleRdb rdb2 = new ExampleRdb(tempDir);
        try (Connection conn = rdb2.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY id")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("Alice");
            assertThat(rs.getInt(3)).isEqualTo(30);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(rs.getString(2)).isEqualTo("Bob");
            assertThat(rs.getInt(3)).isEqualTo(25);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
            assertThat(rs.getString(2)).isEqualTo("Charlie");
            assertThat(rs.getInt(3)).isEqualTo(35);

            assertThat(rs.next()).isFalse();
        }
        rdb2.close();
    }

    @Test
    void recoverWithUncheckpointedWal() throws Exception {
        ExampleTable.ColumnDef[] columns = {
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR)
        };

        ExampleRdb rdb1 = new ExampleRdb(tempDir);
        rdb1.createTable("items", columns);

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO items VALUES (1, 'Apple')");
        }
        rdb1.checkpoint();

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO items VALUES (2, 'Banana')");
            stmt.executeUpdate("INSERT INTO items VALUES (3, 'Cherry')");
        }
        rdb1.close();

        ExampleRdb rdb2 = new ExampleRdb(tempDir);
        try (Connection conn = rdb2.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM items ORDER BY id")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("Apple");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(rs.getString(2)).isEqualTo("Banana");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
            assertThat(rs.getString(2)).isEqualTo("Cherry");

            assertThat(rs.next()).isFalse();
        }
        rdb2.close();
    }

    @Test
    void checkpointClearsOldWal() throws Exception {
        ExampleTable.ColumnDef[] columns = {
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER)
        };

        ExampleRdb rdb1 = new ExampleRdb(tempDir);
        rdb1.createTable("nums", columns);

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO nums VALUES (1)");
        }

        List<WalRecord> beforeCheckpoint = rdb1.getWalManager().readAllSegments();
        long insertCount = beforeCheckpoint.stream()
                .filter(r -> r.getOperation() == WalOperation.INSERT)
                .count();
        assertThat(insertCount).isGreaterThan(0);

        rdb1.checkpoint();

        List<WalRecord> afterCheckpoint = rdb1.getWalManager().readAllSegments();
        assertThat(afterCheckpoint.stream()
                .filter(r -> r.getOperation() == WalOperation.INSERT)
                .count()).isEqualTo(0);

        rdb1.close();
    }

    @Test
    void multipleTablesPersistence() throws Exception {
        ExampleRdb rdb1 = new ExampleRdb(tempDir);
        rdb1.createTable("users",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR));
        rdb1.createTable("orders",
                new ExampleTable.ColumnDef("oid", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("uid", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("amount", SqlTypeName.DOUBLE));

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");
            stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob')");
            stmt.executeUpdate("INSERT INTO orders VALUES (101, 1, 99.99)");
            stmt.executeUpdate("INSERT INTO orders VALUES (102, 2, 50.00)");
        }

        rdb1.checkpoint();
        rdb1.close();

        ExampleRdb rdb2 = new ExampleRdb(tempDir);
        try (Connection conn = rdb2.getConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT name FROM users ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Alice");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Bob");
                assertThat(rs.next()).isFalse();
            }

            try (ResultSet rs = stmt.executeQuery("SELECT oid, uid, amount FROM orders ORDER BY oid")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(101);
                assertThat(rs.getInt(2)).isEqualTo(1);
                assertThat(rs.getDouble(3)).isEqualTo(99.99);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(102);
                assertThat(rs.getInt(2)).isEqualTo(2);
                assertThat(rs.getDouble(3)).isEqualTo(50.00);

                assertThat(rs.next()).isFalse();
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.uid ORDER BY o.oid")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Alice");
                assertThat(rs.getDouble(2)).isEqualTo(99.99);

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Bob");
                assertThat(rs.getDouble(2)).isEqualTo(50.00);

                assertThat(rs.next()).isFalse();
            }
        }
        rdb2.close();
    }
}

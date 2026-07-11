package com.example.rdb;

import com.example.rdb.schema.ExampleTable;
import com.example.rdb.wal.WalOperation;
import com.example.rdb.wal.WalRecord;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsertWalTest {

    @TempDir
    Path tempDir;

    private ExampleRdb rdb;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        rdb = new ExampleRdb(tempDir);
        rdb.createTable("users",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR),
                new ExampleTable.ColumnDef("age", SqlTypeName.INTEGER));

        connection = rdb.getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        rdb.close();
    }

    @Test
    void insertViaJdbcAndVerifyWal() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 30)");
            stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob', 25)");
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("Alice");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(rs.getString(2)).isEqualTo("Bob");

            assertThat(rs.next()).isFalse();
        }

        List<WalRecord> records = rdb.getWalManager().readAllRecords();
        long insertCount = records.stream()
                .filter(r -> r.getOperation() == WalOperation.INSERT)
                .count();
        assertThat(insertCount).isEqualTo(2);

        WalRecord firstInsert = records.stream()
                .filter(r -> r.getOperation() == WalOperation.INSERT)
                .findFirst()
                .orElseThrow();
        assertThat(firstInsert.getTableName()).isEqualTo("users");
        assertThat(firstInsert.getValue("name")).isEqualTo("Alice");
        assertThat(firstInsert.getValue("age")).isEqualTo(30L);
    }

    @Test
    void multiRowInsert() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 30), (2, 'Bob', 25), (3, 'Charlie', 35)");
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("cnt")).isEqualTo(3);
        }

        List<WalRecord> records = rdb.getWalManager().readAllRecords();
        long insertCount = records.stream()
                .filter(r -> r.getOperation() == WalOperation.INSERT)
                .count();
        assertThat(insertCount).isEqualTo(3);
    }

    @Test
    void insertAndSelectWithWhere() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 30)");
            stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob', 25)");
            stmt.executeUpdate("INSERT INTO users VALUES (3, 'Charlie', 35)");
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE age >= 30 ORDER BY age")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Alice");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Charlie");
            assertThat(rs.next()).isFalse();
        }
    }
}

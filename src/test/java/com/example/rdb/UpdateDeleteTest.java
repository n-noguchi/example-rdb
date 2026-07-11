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
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateDeleteTest {

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

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice', 30)");
            stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob', 25)");
            stmt.executeUpdate("INSERT INTO users VALUES (3, 'Charlie', 35)");
            stmt.executeUpdate("INSERT INTO users VALUES (4, 'Diana', 28)");
            stmt.executeUpdate("INSERT INTO users VALUES (5, 'Eve', 40)");
        }

        connection = rdb.getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        rdb.close();
    }

    @Nested
    @DisplayName("DELETE")
    class DeleteTests {

        @Test
        @DisplayName("WHERE条件で特定行をDELETE")
        void deleteWithWhere() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("DELETE FROM users WHERE id = 3");
                assertThat(affected).isEqualTo(1);
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id FROM users ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(4);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(5);
                assertThat(rs.next()).isFalse();
            }
        }

        @Test
        @DisplayName("複数行DELETE")
        void deleteMultipleRows() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("DELETE FROM users WHERE age >= 30");
                assertThat(affected).isEqualTo(3);
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
                assertThat(rs.next()).isTrue();
                assertThat(((Number) rs.getObject(1)).intValue()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("WHERE無しで全行DELETE")
        void deleteAll() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("DELETE FROM users");
                assertThat(affected).isEqualTo(5);
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
                assertThat(rs.next()).isTrue();
                assertThat(((Number) rs.getObject(1)).intValue()).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("該当なしのDELETE")
        void deleteNoMatch() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("DELETE FROM users WHERE id = 999");
                assertThat(affected).isEqualTo(0);
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
                assertThat(rs.next()).isTrue();
                assertThat(((Number) rs.getObject(1)).intValue()).isEqualTo(5);
            }
        }

        @Test
        @DisplayName("DELETE後にINSERT可能")
        void deleteThenInsert() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM users WHERE id = 2");
                stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bobby', 26)");
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = 2")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Bobby");
            }
        }
    }

    @Nested
    @DisplayName("UPDATE")
    class UpdateTests {

        @Test
        @DisplayName("WHERE条件で特定行をUPDATE（単一カラム）")
        void updateSingleColumn() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("UPDATE users SET age = 31 WHERE id = 1");
                assertThat(affected).isEqualTo(1);
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT age FROM users WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(31);
            }
        }

        @Test
        @DisplayName("複数カラムUPDATE")
        void updateMultipleColumns() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE users SET name = 'Alicia', age = 32 WHERE id = 1");
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name, age FROM users WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Alicia");
                assertThat(rs.getInt(2)).isEqualTo(32);
            }
        }

        @Test
        @DisplayName("WHERE無しで全行UPDATE")
        void updateAllRows() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("UPDATE users SET age = 0");
                assertThat(affected).isEqualTo(5);
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT DISTINCT age FROM users")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
                assertThat(rs.next()).isFalse();
            }
        }

        @Test
        @DisplayName("複数行UPDATE（WHERE条件）")
        void updateMultipleRows() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("UPDATE users SET name = 'Young' WHERE age < 30");
                assertThat(affected).isEqualTo(2);
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE age < 30 ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Young");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Young");
                assertThat(rs.next()).isFalse();
            }
        }

        @Test
        @DisplayName("SET値にNULL指定")
        void updateSetNull() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE users SET name = NULL WHERE id = 1");
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
            }
        }

        @Test
        @DisplayName("SET値にカラム参照")
        void updateWithColumnReference() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE users SET name = name WHERE id = 1");
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Alice");
            }
        }

        @Test
        @DisplayName("該当なしのUPDATE")
        void updateNoMatch() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                int affected = stmt.executeUpdate("UPDATE users SET age = 99 WHERE id = 999");
                assertThat(affected).isEqualTo(0);
            }
        }
    }

    @Nested
    @DisplayName("DELETE + UPDATE + 永続化")
    class PersistenceTests {

        @Test
        @DisplayName("DELETE後にチェックポイント→再起動でデータ確認")
        void deleteCheckpointRecover() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM users WHERE id IN (4, 5)");
            }
            connection.close();
            rdb.checkpoint();
            rdb.close();

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            try (Connection conn = rdb2.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
                assertThat(rs.next()).isTrue();
                assertThat(((Number) rs.getObject(1)).intValue()).isEqualTo(3);
            }

            try (Connection conn = rdb2.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id FROM users ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(3);
                assertThat(rs.next()).isFalse();
            }
            rdb2.close();
        }

        @Test
        @DisplayName("UPDATE後にチェックポイント→再起動でデータ確認")
        void updateCheckpointRecover() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE users SET name = 'Updated', age = 50 WHERE id = 1");
            }
            connection.close();
            rdb.checkpoint();
            rdb.close();

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            try (Connection conn = rdb2.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name, age FROM users WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Updated");
                assertThat(rs.getInt(2)).isEqualTo(50);
            }
            rdb2.close();
        }

        @Test
        @DisplayName("DELETE/UPDATE後にWALリカバリでデータ確認（チェックポイント無し）")
        void deleteUpdateWalRecovery() throws Exception {
            rdb.checkpoint();

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM users WHERE id = 1");
                stmt.executeUpdate("UPDATE users SET name = 'Changed' WHERE id = 2");
            }
            connection.close();
            rdb.close();

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            try (Connection conn = rdb2.getConnection();
                 Statement stmt = conn.createStatement()) {

                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(((Number) rs.getObject(1)).intValue()).isEqualTo(4);
                }

                try (ResultSet rs = stmt.executeQuery("SELECT id FROM users WHERE id = 1")) {
                    assertThat(rs.next()).isFalse();
                }

                try (ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = 2")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("Changed");
                }
            }
            rdb2.close();
        }
    }

    @Test
    @DisplayName("チェックポイント後にDELETE→mmap Base行も削除確認")
    void deleteFromBaseAfterCheckpoint() throws Exception {
        rdb.checkpoint();

        try (Statement stmt = connection.createStatement()) {
            int affected = stmt.executeUpdate("DELETE FROM users WHERE id = 1");
            assertThat(affected).isEqualTo(1);
        }

        ExampleTable table = rdb.getSchema().getExampleTable("users");
        assertThat(table.getDeletedRows()).hasSize(1);

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(((Number) rs.getObject(1)).intValue()).isEqualTo(4);
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM users WHERE id = 1")) {
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    @DisplayName("チェックポイント後にUPDATE→mmap Base行が置換確認")
    void updateFromBaseAfterCheckpoint() throws Exception {
        rdb.checkpoint();

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE users SET name = 'Updated' WHERE id = 1");
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Updated");
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {
            assertThat(rs.next()).isTrue();
            assertThat(((Number) rs.getObject(1)).intValue()).isEqualTo(5);
        }
    }
}

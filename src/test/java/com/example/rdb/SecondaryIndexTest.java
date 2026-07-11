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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecondaryIndexTest {

    @TempDir
    Path tempDir;

    private ExampleRdb rdb;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        rdb = new ExampleRdb(tempDir);
        rdb.createTable("users",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("email", SqlTypeName.VARCHAR),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR),
                new ExampleTable.ColumnDef("age", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("active", SqlTypeName.BOOLEAN));

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'alice@ex.com', 'Alice', 30, TRUE)");
            stmt.executeUpdate("INSERT INTO users VALUES (2, 'bob@ex.com', 'Bob', 25, FALSE)");
            stmt.executeUpdate("INSERT INTO users VALUES (3, 'charlie@ex.com', 'Charlie', 35, TRUE)");
            stmt.executeUpdate("INSERT INTO users VALUES (4, 'diana@ex.com', 'Diana', 28, TRUE)");
            stmt.executeUpdate("INSERT INTO users VALUES (5, 'eve@ex.com', 'Eve', 40, TRUE)");
        }

        connection = rdb.getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        rdb.close();
    }

    @Nested
    @DisplayName("DDL: CREATE INDEX / DROP INDEX")
    class DdlTests {

        @Test
        @DisplayName("CREATE INDEX with INCLUDE")
        void createIndexWithInclude() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name, active)");
            }
            assertThat(rdb.getSchema().getExampleTable("users").getIndexManager().hasIndex("idx_email")).isTrue();
        }

        @Test
        @DisplayName("CREATE INDEX without INCLUDE")
        void createIndexWithoutInclude() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_age ON users(age)");
            }
            assertThat(rdb.getSchema().getExampleTable("users").getIndexManager().hasIndex("idx_age")).isTrue();
        }

        @Test
        @DisplayName("DROP INDEX")
        void dropIndex() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name)");
                stmt.execute("DROP INDEX idx_email ON users");
            }
            assertThat(rdb.getSchema().getExampleTable("users").getIndexManager().hasIndex("idx_email")).isFalse();
        }

        @Test
        @DisplayName("DROP INDEX IF EXISTS on non-existent index")
        void dropIndexIfExists() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP INDEX IF EXISTS nonexistent ON users");
            }
        }

        @Test
        @DisplayName("Duplicate index name rejected")
        void duplicateIndexName() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name)");
                assertThatThrownBy(() -> stmt.execute("CREATE INDEX idx_email ON users(email)"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }

        @Test
        @DisplayName("Index on non-existent column rejected")
        void indexOnNonExistentColumn() {
            assertThatThrownBy(() -> {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("CREATE INDEX idx_bad ON users(nonexistent)");
                }
            }).isInstanceOf(java.sql.SQLException.class);
        }

        @Test
        @DisplayName("DROP TABLE also drops indexes")
        void dropTableDropsIndexes() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name)");
            }
            rdb.dropTable("users");
            // No exception - indexes cleaned up
        }
    }

    @Nested
    @DisplayName("Covering Index Scan: Equality Lookup")
    class EqualityScanTests {

        @BeforeEach
        void createIndex() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name, age, active)");
            }
        }

        @Test
        @DisplayName("Exact match returns correct row")
        void exactMatch() throws Exception {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name, age FROM users WHERE email = 'alice@ex.com'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Alice");
                assertThat(rs.getInt("age")).isEqualTo(30);
                assertThat(rs.next()).isFalse();
            }
        }

        @Test
        @DisplayName("No match returns empty")
        void noMatch() throws Exception {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE email = 'nobody@ex.com'")) {
                assertThat(rs.next()).isFalse();
            }
        }

        @Test
        @DisplayName("All covered columns returned")
        void allCoveredColumns() throws Exception {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name, age, active FROM users WHERE email = 'bob@ex.com'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("Bob");
                assertThat(rs.getInt("age")).isEqualTo(25);
                assertThat(rs.getBoolean("active")).isFalse();
            }
        }

        @Test
        @DisplayName("Non-covered query falls back to full scan")
        void nonCoveredFallsBack() throws Exception {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, name FROM users WHERE email = 'alice@ex.com'")) {
                // 'id' is not in the index (key=email, include=name,age,active)
                // Should fall back to Calcite full scan
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("id")).isEqualTo(1);
                assertThat(rs.getString("name")).isEqualTo("Alice");
            }
        }
    }

    @Nested
    @DisplayName("Covering Index Scan: Range Lookup")
    class RangeScanTests {

        @BeforeEach
        void createIndex() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_age ON users(age) INCLUDE (name)");
            }
        }

        @Test
        @DisplayName("Greater than range")
        void greaterThan() throws Exception {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE age > 30")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isIn("Charlie", "Eve");
                assertThat(rs.next()).isTrue();
                assertThat(rs.next()).isFalse();
            }
        }

        @Test
        @DisplayName("Between range (>= AND <=)")
        void betweenRange() throws Exception {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE age >= 28 AND age <= 35")) {
                int count = 0;
                while (rs.next()) count++;
                assertThat(count).isEqualTo(3); // Diana(28), Alice(30), Charlie(35)
            }
        }
    }

    @Nested
    @DisplayName("Index Maintenance on DML")
    class DmlMaintenanceTests {

        @BeforeEach
        void createIndex() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name)");
            }
        }

        @Test
        @DisplayName("INSERT after CREATE INDEX is visible via index")
        void insertAfterCreate() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("INSERT INTO users VALUES (6, 'frank@ex.com', 'Frank', 50, TRUE)");

                try (ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE email = 'frank@ex.com'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Frank");
                }
            }
        }

        @Test
        @DisplayName("DELETE removes entry from index")
        void deleteRemovesEntry() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM users WHERE email = 'alice@ex.com'");

                try (ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE email = 'alice@ex.com'")) {
                    assertThat(rs.next()).isFalse();
                }
            }
        }

        @Test
        @DisplayName("UPDATE changes index entry")
        void updateChangesEntry() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("UPDATE users SET name = 'Alicia' WHERE email = 'alice@ex.com'");

                try (ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE email = 'alice@ex.com'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Alicia");
                }
            }
        }
    }

    @Nested
    @DisplayName("Persistence and Recovery")
    class PersistenceTests {

        @Test
        @DisplayName("Index survives checkpoint and restart")
        void indexSurvivesCheckpoint() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name)");
            }
            connection.close();
            rdb.checkpoint();
            rdb.close();

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            try (Connection conn = rdb2.getConnection();
                 Statement stmt = conn.createStatement()) {

                // Index should be restored from catalog and index Arrow file
                assertThat(rdb2.getSchema().getExampleTable("users").getIndexManager()).isNotNull();
                assertThat(rdb2.getSchema().getExampleTable("users").getIndexManager().hasIndex("idx_email")).isTrue();

                try (ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE email = 'bob@ex.com'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Bob");
                }
            }
            rdb2.close();
        }

        @Test
        @DisplayName("Index survives WAL recovery without checkpoint")
        void indexWalRecovery() throws Exception {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE INDEX idx_email ON users(email) INCLUDE (name)");
            }
            rdb.checkpoint();

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("INSERT INTO users VALUES (6, 'frank@ex.com', 'Frank', 50, TRUE)");
            }
            connection.close();
            rdb.close();

            ExampleRdb rdb2 = new ExampleRdb(tempDir);
            try (Connection conn = rdb2.getConnection();
                 Statement stmt = conn.createStatement()) {

                assertThat(rdb2.getSchema().getExampleTable("users").getIndexManager().hasIndex("idx_email")).isTrue();

                // Frank was inserted after checkpoint - should be in delta index
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE email = 'frank@ex.com'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Frank");
                }

                // Bob was in the checkpoint - should be in base index
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE email = 'bob@ex.com'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Bob");
                }
            }
            rdb2.close();
        }
    }

    @Nested
    @DisplayName("Composite Index")
    class CompositeIndexTests {

        @Test
        @DisplayName("Composite key index works with leading column equality")
        void compositeKeyLeadingColumn() throws Exception {
            rdb.createTable("orders",
                    new ExampleTable.ColumnDef("oid", SqlTypeName.INTEGER),
                    new ExampleTable.ColumnDef("customer_id", SqlTypeName.INTEGER),
                    new ExampleTable.ColumnDef("status", SqlTypeName.VARCHAR),
                    new ExampleTable.ColumnDef("amount", SqlTypeName.DOUBLE));

            try (Connection conn = rdb.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO orders VALUES (1, 100, 'shipped', 50.00)");
                stmt.executeUpdate("INSERT INTO orders VALUES (2, 100, 'pending', 30.00)");
                stmt.executeUpdate("INSERT INTO orders VALUES (3, 200, 'shipped', 75.00)");

                stmt.execute("CREATE INDEX idx_customer_status ON orders(customer_id, status) INCLUDE (amount)");
            }

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT amount FROM orders WHERE customer_id = 100")) {
                int count = 0;
                while (rs.next()) {
                    assertThat(rs.getDouble("amount")).isIn(50.0, 30.0);
                    count++;
                }
                assertThat(count).isEqualTo(2);
            }
        }
    }
}

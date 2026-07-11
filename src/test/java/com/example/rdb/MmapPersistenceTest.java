package com.example.rdb;

import com.example.rdb.schema.ExampleTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class MmapPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void checkpointThenScanLazilyFromArrowFile() throws Exception {
        ExampleRdb rdb1 = new ExampleRdb(tempDir);
        rdb1.createTable("users",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR));
        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");
            stmt.executeUpdate("INSERT INTO users VALUES (2, 'Bob')");
            stmt.executeUpdate("INSERT INTO users VALUES (3, 'Charlie')");
        }
        rdb1.checkpoint();

        ExampleTable table = rdb1.getSchema().getExampleTable("users");
        assertThat(table.getDeltaRows()).as("Delta should be cleared after checkpoint").isEmpty();
        assertThat(table.getBaseDataPath()).as("Base path should be set").isNotNull();

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("Alice");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(rs.getString(2)).isEqualTo("Bob");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
            assertThat(rs.getString(2)).isEqualTo("Charlie");
            assertThat(rs.next()).isFalse();
        }
        rdb1.close();
    }

    @Test
    void insertAfterCheckpointMergesBaseAndDelta() throws Exception {
        ExampleRdb rdb = new ExampleRdb(tempDir);
        rdb.createTable("items",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR));

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO items VALUES (1, 'Apple')");
            stmt.executeUpdate("INSERT INTO items VALUES (2, 'Banana')");
        }
        rdb.checkpoint();

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO items VALUES (3, 'Cherry')");
            stmt.executeUpdate("INSERT INTO items VALUES (4, 'Date')");
        }

        ExampleTable table = rdb.getSchema().getExampleTable("items");
        assertThat(table.getBaseDataPath()).isNotNull();
        assertThat(table.getDeltaRows()).hasSize(2);

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM items ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("Apple");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("Banana");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("Cherry");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("Date");
            assertThat(rs.next()).isFalse();
        }
        rdb.close();
    }

    @Test
    void recoveryUsesBasePathWithoutLoadingAllIntoMemory() throws Exception {
        ExampleRdb rdb1 = new ExampleRdb(tempDir);
        rdb1.createTable("logs",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("msg", SqlTypeName.VARCHAR));
        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO logs VALUES (1, 'first')");
            stmt.executeUpdate("INSERT INTO logs VALUES (2, 'second')");
        }
        rdb1.checkpoint();
        rdb1.close();

        ExampleRdb rdb2 = new ExampleRdb(tempDir);
        ExampleTable table = rdb2.getSchema().getExampleTable("logs");
        assertThat(table.getBaseDataPath()).as("Recovery should set base path, not load rows").isNotNull();
        assertThat(table.getDeltaRows()).as("Delta should be empty after recovery with no WAL").isEmpty();

        try (Connection conn = rdb2.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM logs ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("first");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("second");
            assertThat(rs.next()).isFalse();
        }
        rdb2.close();
    }

    @Test
    void recoveryMergesBaseAndWalDelta() throws Exception {
        ExampleRdb rdb1 = new ExampleRdb(tempDir);
        rdb1.createTable("data",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER));
        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO data VALUES (10)");
            stmt.executeUpdate("INSERT INTO data VALUES (20)");
        }
        rdb1.checkpoint();

        try (Connection conn = rdb1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO data VALUES (30)");
            stmt.executeUpdate("INSERT INTO data VALUES (40)");
        }
        rdb1.close();

        ExampleRdb rdb2 = new ExampleRdb(tempDir);
        ExampleTable table = rdb2.getSchema().getExampleTable("data");
        assertThat(table.getBaseDataPath()).isNotNull();
        assertThat(table.getDeltaRows()).as("WAL records should be replayed into delta").hasSize(2);

        try (Connection conn = rdb2.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM data ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(10);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(20);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(30);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(40);
            assertThat(rs.next()).isFalse();
        }
        rdb2.close();
    }

    @Test
    void multipleCheckpointsAccumulateData() throws Exception {
        ExampleRdb rdb = new ExampleRdb(tempDir);
        rdb.createTable("counter",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER));

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO counter VALUES (1)");
        }
        rdb.checkpoint();

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO counter VALUES (2)");
        }
        rdb.checkpoint();

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO counter VALUES (3)");
        }
        rdb.checkpoint();

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM counter ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
            assertThat(rs.next()).isFalse();
        }
        rdb.close();
    }

    @Test
    void emptyTableCheckpointAndScan() throws Exception {
        ExampleRdb rdb = new ExampleRdb(tempDir);
        rdb.createTable("blank_tbl",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR));
        rdb.checkpoint();

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM blank_tbl")) {
            assertThat(rs.next()).isFalse();
        }

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO blank_tbl VALUES (1, 'data')");
        }

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM blank_tbl")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("data");
        }
        rdb.close();
    }

    @Test
    void largeBatchCountTriggeredByManyRows() throws Exception {
        ExampleRdb rdb = new ExampleRdb(tempDir);
        rdb.createTable("big",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("val", SqlTypeName.VARCHAR));

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            for (int i = 0; i < 10000; i++) {
                stmt.executeUpdate("INSERT INTO big VALUES (" + i + ", 'val" + i + "')");
            }
        }
        rdb.checkpoint();

        try (Connection conn = rdb.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet countRs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM big")) {
                assertThat(countRs.next()).isTrue();
                assertThat(((Number) countRs.getObject(1)).longValue()).isEqualTo(10000);
            }

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM big ORDER BY id LIMIT 3")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(0);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM big WHERE id >= 9998 ORDER BY id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(9998);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(9999);
                assertThat(rs.next()).isFalse();
            }
        }
        rdb.close();
    }
}

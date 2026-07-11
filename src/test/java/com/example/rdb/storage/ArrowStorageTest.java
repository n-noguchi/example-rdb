package com.example.rdb.storage;

import com.example.rdb.schema.ExampleTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArrowStorageTest {

    @TempDir
    Path tempDir;

    private ArrowStorage storage;

    @BeforeEach
    void setUp() {
        storage = new ArrowStorage();
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void writeAndReadTable() throws Exception {
        ExampleTable table = new ExampleTable("users", List.of(
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR),
                new ExampleTable.ColumnDef("age", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("active", SqlTypeName.BOOLEAN)
        ));

        table.addRow(new Object[]{1, "Alice", 30, true});
        table.addRow(new Object[]{2, "Bob", 25, false});
        table.addRow(new Object[]{3, "Charlie", 35, true});

        Path arrowFile = tempDir.resolve("users.arrow");
        storage.writeTable(arrowFile, table);

        assertThat(arrowFile.toFile().exists()).isTrue();
        assertThat(arrowFile.toFile().length()).isGreaterThan(0);

        List<Object[]> rows = storage.readTable(arrowFile, table.getColumns());
        assertThat(rows).hasSize(3);

        assertThat(rows.get(0)[0]).isEqualTo(1);
        assertThat(rows.get(0)[1]).isEqualTo("Alice");
        assertThat(rows.get(0)[2]).isEqualTo(30);
        assertThat(rows.get(0)[3]).isEqualTo(true);

        assertThat(rows.get(1)[0]).isEqualTo(2);
        assertThat(rows.get(1)[1]).isEqualTo("Bob");
        assertThat(rows.get(1)[2]).isEqualTo(25);
        assertThat(rows.get(1)[3]).isEqualTo(false);

        assertThat(rows.get(2)[0]).isEqualTo(3);
        assertThat(rows.get(2)[1]).isEqualTo("Charlie");
        assertThat(rows.get(2)[2]).isEqualTo(35);
        assertThat(rows.get(2)[3]).isEqualTo(true);
    }

    @Test
    void emptyTable() throws Exception {
        ExampleTable table = new ExampleTable("empty", List.of(
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR)
        ));

        Path arrowFile = tempDir.resolve("empty.arrow");
        storage.writeTable(arrowFile, table);

        List<Object[]> rows = storage.readTable(arrowFile, table.getColumns());
        assertThat(rows).isEmpty();
    }

    @Test
    void overwriteTable() throws Exception {
        ExampleTable table = new ExampleTable("t", List.of(
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR)
        ));

        table.addRow(new Object[]{1, "Alice"});
        Path arrowFile = tempDir.resolve("t.arrow");
        storage.writeTable(arrowFile, table);

        table.addRow(new Object[]{2, "Bob"});
        table.addRow(new Object[]{3, "Charlie"});
        storage.writeTable(arrowFile, table);

        List<Object[]> rows = storage.readTable(arrowFile, table.getColumns());
        assertThat(rows).hasSize(3);
    }

    @Test
    void nullValues() throws Exception {
        ExampleTable table = new ExampleTable("nullable", List.of(
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR)
        ));

        table.addRow(new Object[]{1, "Alice"});
        table.addRow(new Object[]{2, null});
        table.addRow(new Object[]{3, "Charlie"});

        Path arrowFile = tempDir.resolve("nullable.arrow");
        storage.writeTable(arrowFile, table);

        List<Object[]> rows = storage.readTable(arrowFile, table.getColumns());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)[1]).isEqualTo("Alice");
        assertThat(rows.get(1)[1]).isNull();
        assertThat(rows.get(2)[1]).isEqualTo("Charlie");
    }

    @Test
    void bigIntAndDouble() throws Exception {
        ExampleTable table = new ExampleTable("nums", List.of(
                new ExampleTable.ColumnDef("big", SqlTypeName.BIGINT),
                new ExampleTable.ColumnDef("dbl", SqlTypeName.DOUBLE)
        ));

        table.addRow(new Object[]{9223372036854775807L, 3.14159});
        table.addRow(new Object[]{-9223372036854775808L, -2.71828});

        Path arrowFile = tempDir.resolve("nums.arrow");
        storage.writeTable(arrowFile, table);

        List<Object[]> rows = storage.readTable(arrowFile, table.getColumns());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo(9223372036854775807L);
        assertThat((double) rows.get(0)[1]).isEqualTo(3.14159);
        assertThat(rows.get(1)[0]).isEqualTo(-9223372036854775808L);
        assertThat((double) rows.get(1)[1]).isEqualTo(-2.71828);
    }
}

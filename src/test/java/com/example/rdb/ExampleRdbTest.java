package com.example.rdb;

import com.example.rdb.schema.ExampleTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class ExampleRdbTest {

    private ExampleRdb rdb;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        rdb = new ExampleRdb();
        rdb.createTable("users",
                new ExampleTable.ColumnDef("id", SqlTypeName.INTEGER),
                new ExampleTable.ColumnDef("name", SqlTypeName.VARCHAR),
                new ExampleTable.ColumnDef("age", SqlTypeName.INTEGER));

        rdb.insert("users", 1, "Alice", 30);
        rdb.insert("users", 2, "Bob", 25);
        rdb.insert("users", 3, "Charlie", 35);

        connection = rdb.getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void selectAll() throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {

            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(3);
            assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("id");
            assertThat(rs.getMetaData().getColumnName(2)).isEqualTo("name");
            assertThat(rs.getMetaData().getColumnName(3)).isEqualTo("age");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("Alice");
            assertThat(rs.getInt(3)).isEqualTo(30);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(rs.getString(2)).isEqualTo("Bob");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
            assertThat(rs.getString(2)).isEqualTo("Charlie");

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void selectWithWhere() throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM users WHERE age >= 30")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Alice");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Charlie");

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void selectWithOrderBy() throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY age DESC")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("age")).isEqualTo(35);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("age")).isEqualTo(30);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("age")).isEqualTo(25);
        }
    }

    @Test
    void selectCount() throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM users")) {

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("cnt")).isEqualTo(3);
        }
    }
}

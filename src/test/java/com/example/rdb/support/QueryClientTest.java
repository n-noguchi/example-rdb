package com.example.rdb.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryClientTest {

    @TempDir
    Path tempDir;

    private QueryClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new QueryClient(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
    }

    private void setupUsersTable() throws Exception {
        client.createTable("users",
                QueryClient.ColumnSpec.intCol("id"),
                QueryClient.ColumnSpec.varcharCol("name"),
                QueryClient.ColumnSpec.intCol("age"));
        client.execute("INSERT INTO users VALUES (1, 'Alice', 30)");
        client.execute("INSERT INTO users VALUES (2, 'Bob', 25)");
        client.execute("INSERT INTO users VALUES (3, 'Charlie', 35)");
        client.execute("INSERT INTO users VALUES (4, 'Diana', 28)");
        client.execute("INSERT INTO users VALUES (5, 'Eve', 40)");
    }

    private void setupOrdersTable() throws Exception {
        client.createTable("orders",
                QueryClient.ColumnSpec.intCol("oid"),
                QueryClient.ColumnSpec.intCol("uid"),
                QueryClient.ColumnSpec.varcharCol("product"),
                QueryClient.ColumnSpec.doubleCol("amount"));
        client.execute("INSERT INTO orders VALUES (101, 1, 'Laptop', 999.99)");
        client.execute("INSERT INTO orders VALUES (102, 2, 'Phone', 599.00)");
        client.execute("INSERT INTO orders VALUES (103, 1, 'Mouse', 29.99)");
        client.execute("INSERT INTO orders VALUES (104, 3, 'Keyboard', 79.99)");
        client.execute("INSERT INTO orders VALUES (105, 5, 'Monitor', 349.99)");
    }

    @Nested
    @DisplayName("CREATE TABLE & INSERT")
    class CreateTableAndInsert {

        @Test
        @DisplayName("CREATE TABLE後にSELECTで0件確認")
        void createTableThenSelectEmpty() throws Exception {
            client.createTable("t",
                    QueryClient.ColumnSpec.intCol("id"),
                    QueryClient.ColumnSpec.varcharCol("name"));

            QueryResult result = client.execute("SELECT * FROM t");
            assertThat(result.isQuery()).isTrue();
            assertThat(result.getRowCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("単一行INSERT")
        void singleInsert() throws Exception {
            client.createTable("t",
                    QueryClient.ColumnSpec.intCol("id"),
                    QueryClient.ColumnSpec.varcharCol("name"));
            QueryResult result = client.execute("INSERT INTO t VALUES (1, 'hello')");
            assertThat(result.isQuery()).isFalse();
            assertThat(result.getAffectedRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("複数行INSERT（1文で複数VALUES）")
        void multiRowInsert() throws Exception {
            client.createTable("t", QueryClient.ColumnSpec.intCol("id"));
            QueryResult result = client.execute("INSERT INTO t VALUES (10), (20), (30)");
            assertThat(result.getAffectedRows()).isEqualTo(3);

            QueryResult select = client.execute("SELECT * FROM t ORDER BY id");
            assertThat(select.getRowCount()).isEqualTo(3);
            assertThat(select.getValue(0, 0)).isEqualTo(10);
            assertThat(select.getValue(1, 0)).isEqualTo(20);
            assertThat(select.getValue(2, 0)).isEqualTo(30);
        }

        @Test
        @DisplayName("NULL値のINSERT")
        void insertNullValue() throws Exception {
            client.createTable("t",
                    QueryClient.ColumnSpec.intCol("id"),
                    QueryClient.ColumnSpec.varcharCol("name"));
            client.execute("INSERT INTO t VALUES (1, NULL)");
            client.execute("INSERT INTO t VALUES (2, 'Bob')");

            QueryResult result = client.execute("SELECT * FROM t ORDER BY id");
            assertThat(result.getValue(0, 1)).isNull();
            assertThat(result.getValue(1, 1)).isEqualTo("Bob");
        }
    }

    @Nested
    @DisplayName("SELECT パターン")
    class SelectPatterns {

        @BeforeEach
        void setUpData() throws Exception {
            setupUsersTable();
        }

        @Test
        @DisplayName("SELECT * 全件取得")
        void selectAll() throws Exception {
            QueryResult result = client.execute("SELECT * FROM users ORDER BY id");
            assertThat(result.getRowCount()).isEqualTo(5);
            assertThat(result.getColumnNames()).containsExactly("id", "name", "age");
            assertThat(result.getValue(0, 0)).isEqualTo(1);
            assertThat(result.getValue(0, 1)).isEqualTo("Alice");
            assertThat(result.getValue(0, 2)).isEqualTo(30);
        }

        @Test
        @DisplayName("SELECT 特定カラム")
        void selectSpecificColumns() throws Exception {
            QueryResult result = client.execute("SELECT name, age FROM users WHERE id = 1");
            assertThat(result.getRowCount()).isEqualTo(1);
            assertThat(result.getColumnNames()).containsExactly("name", "age");
            assertThat(result.getValue(0, 0)).isEqualTo("Alice");
            assertThat(result.getValue(0, 1)).isEqualTo(30);
        }

        @Test
        @DisplayName("WHERE条件（数値比較 >=）")
        void whereNumericGte() throws Exception {
            QueryResult result = client.execute("SELECT name FROM users WHERE age >= 30 ORDER BY name");
            assertThat(result.getRowCount()).isEqualTo(3);
            assertThat(result.getValue(0, 0)).isEqualTo("Alice");
            assertThat(result.getValue(1, 0)).isEqualTo("Charlie");
            assertThat(result.getValue(2, 0)).isEqualTo("Eve");
        }

        @Test
        @DisplayName("WHERE条件（数値比較 < AND >）")
        void whereBetween() throws Exception {
            QueryResult result = client.execute("SELECT name FROM users WHERE age > 25 AND age < 40 ORDER BY age");
            assertThat(result.getRowCount()).isEqualTo(3);
            assertThat(result.getValue(0, 0)).isEqualTo("Diana");
            assertThat(result.getValue(1, 0)).isEqualTo("Alice");
            assertThat(result.getValue(2, 0)).isEqualTo("Charlie");
        }

        @Test
        @DisplayName("WHERE条件（文字列一致 =）")
        void whereStringEquals() throws Exception {
            QueryResult result = client.execute("SELECT * FROM users WHERE name = 'Bob'");
            assertThat(result.getRowCount()).isEqualTo(1);
            assertThat(result.getValue(0, 0)).isEqualTo(2);
        }

        @Test
        @DisplayName("ORDER BY ASC（昇順）")
        void orderByAsc() throws Exception {
            QueryResult result = client.execute("SELECT name FROM users ORDER BY age ASC");
            assertThat(result.getRowCount()).isEqualTo(5);
            assertThat(result.getValue(0, 0)).isEqualTo("Bob");
            assertThat(result.getValue(1, 0)).isEqualTo("Diana");
            assertThat(result.getValue(2, 0)).isEqualTo("Alice");
            assertThat(result.getValue(3, 0)).isEqualTo("Charlie");
            assertThat(result.getValue(4, 0)).isEqualTo("Eve");
        }

        @Test
        @DisplayName("ORDER BY DESC（降順）")
        void orderByDesc() throws Exception {
            QueryResult result = client.execute("SELECT name FROM users ORDER BY age DESC");
            assertThat(result.getValue(0, 0)).isEqualTo("Eve");
            assertThat(result.getValue(4, 0)).isEqualTo("Bob");
        }

        @Test
        @DisplayName("COUNT集計")
        void countAll() throws Exception {
            QueryResult result = client.execute("SELECT COUNT(*) AS total FROM users");
            assertThat(result.getRowCount()).isEqualTo(1);
            assertThat(((Number) result.getValue(0, 0)).intValue()).isEqualTo(5);
        }

        @Test
        @DisplayName("COUNT + WHERE条件")
        void countWithWhere() throws Exception {
            QueryResult result = client.execute("SELECT COUNT(*) AS cnt FROM users WHERE age >= 30");
            assertThat(((Number) result.getValue(0, 0)).intValue()).isEqualTo(3);
        }

        @Test
        @DisplayName("MAX / MIN / SUM / AVG集計")
        void aggregateFunctions() throws Exception {
            QueryResult maxResult = client.execute("SELECT MAX(age) AS mx FROM users");
            assertThat(maxResult.getValue(0, 0)).isEqualTo(40);

            QueryResult minResult = client.execute("SELECT MIN(age) AS mn FROM users");
            assertThat(minResult.getValue(0, 0)).isEqualTo(25);

            QueryResult sumResult = client.execute("SELECT SUM(age) AS total_age FROM users");
            assertThat(((Number) sumResult.getValue(0, 0)).intValue()).isEqualTo(158);

            QueryResult avgResult = client.execute("SELECT AVG(age) AS avg_age FROM users");
            assertThat(((Number) avgResult.getValue(0, 0)).intValue()).isEqualTo(31);
        }

        @Test
        @DisplayName("GROUP BY集計")
        void groupBy() throws Exception {
            client.execute("INSERT INTO users VALUES (6, 'Frank', 30)");

            QueryResult result = client.execute(
                    "SELECT age, COUNT(*) AS cnt FROM users GROUP BY age ORDER BY age");
            int age30Count = -1;
            for (int i = 0; i < result.getRowCount(); i++) {
                if (((Number) result.getValue(i, 0)).intValue() == 30) {
                    age30Count = ((Number) result.getValue(i, 1)).intValue();
                }
            }
            assertThat(age30Count).isEqualTo(2);
        }

        @Test
        @DisplayName("LIMIT句")
        void limitClause() throws Exception {
            QueryResult result = client.execute("SELECT name FROM users ORDER BY id LIMIT 3");
            assertThat(result.getRowCount()).isEqualTo(3);
            assertThat(result.getValue(0, 0)).isEqualTo("Alice");
            assertThat(result.getValue(1, 0)).isEqualTo("Bob");
            assertThat(result.getValue(2, 0)).isEqualTo("Charlie");
        }

        @Test
        @DisplayName("DISTINCT重複排除")
        void distinct() throws Exception {
            QueryResult result = client.execute("SELECT DISTINCT age FROM users ORDER BY age");
            for (int i = 1; i < result.getRowCount(); i++) {
                int prev = ((Number) result.getValue(i - 1, 0)).intValue();
                int curr = ((Number) result.getValue(i, 0)).intValue();
                assertThat(prev).isNotEqualTo(curr);
            }
        }
    }

    @Nested
    @DisplayName("JOIN")
    class JoinPatterns {

        @BeforeEach
        void setUpData() throws Exception {
            setupUsersTable();
            setupOrdersTable();
        }

        @Test
        @DisplayName("INNER JOIN でユーザーと注文を結合")
        void innerJoin() throws Exception {
            QueryResult result = client.execute(
                    "SELECT u.name, o.product, o.amount " +
                    "FROM users u INNER JOIN orders o ON u.id = o.uid " +
                    "ORDER BY o.oid");

            assertThat(result.getRowCount()).isEqualTo(5);
            assertThat(result.getColumnNames()).containsExactly("name", "product", "amount");

            assertThat(result.getValue(0, 0)).isEqualTo("Alice");
            assertThat(result.getValue(0, 1)).isEqualTo("Laptop");

            assertThat(result.getValue(1, 0)).isEqualTo("Bob");
            assertThat(result.getValue(1, 1)).isEqualTo("Phone");

            assertThat(result.getValue(2, 0)).isEqualTo("Alice");
            assertThat(result.getValue(2, 1)).isEqualTo("Mouse");
        }

        @Test
        @DisplayName("JOIN + WHERE条件")
        void joinWithWhere() throws Exception {
            QueryResult result = client.execute(
                    "SELECT u.name, o.product " +
                    "FROM users u JOIN orders o ON u.id = o.uid " +
                    "WHERE o.amount > 100 " +
                    "ORDER BY o.oid");

            assertThat(result.getRowCount()).isEqualTo(3);
            assertThat(result.getValue(0, 0)).isEqualTo("Alice");
            assertThat(result.getValue(0, 1)).isEqualTo("Laptop");
            assertThat(result.getValue(1, 0)).isEqualTo("Bob");
            assertThat(result.getValue(1, 1)).isEqualTo("Phone");
            assertThat(result.getValue(2, 0)).isEqualTo("Eve");
            assertThat(result.getValue(2, 1)).isEqualTo("Monitor");
        }

        @Test
        @DisplayName("JOIN + SUM集計（ユーザー別注文合計）")
        void joinWithGroupAggregate() throws Exception {
            QueryResult result = client.execute(
                    "SELECT u.name, SUM(o.amount) AS total " +
                    "FROM users u JOIN orders o ON u.id = o.uid " +
                    "GROUP BY u.name ORDER BY u.name");

            assertThat(result.getRowCount()).isGreaterThan(0);

            for (int i = 0; i < result.getRowCount(); i++) {
                if (result.getValue(i, 0).equals("Alice")) {
                    double aliceTotal = ((Number) result.getValue(i, 1)).doubleValue();
                    assertThat(aliceTotal).isEqualTo(999.99 + 29.99, org.assertj.core.data.Offset.offset(0.01));
                }
            }
        }
    }

    @Nested
    @DisplayName("永続化とリカバリ")
    class Persistence {

        @Test
        @DisplayName("チェックポイント後に新クライアントでデータ確認")
        void checkpointAndReconnect() throws Exception {
            client.createTable("items",
                    QueryClient.ColumnSpec.intCol("id"),
                    QueryClient.ColumnSpec.varcharCol("name"));
            client.execute("INSERT INTO items VALUES (1, 'Apple')");
            client.execute("INSERT INTO items VALUES (2, 'Banana')");
            client.checkpoint();
            client.close();

            try (QueryClient client2 = new QueryClient(tempDir)) {
                QueryResult result = client2.execute("SELECT * FROM items ORDER BY id");
                assertThat(result.getRowCount()).isEqualTo(2);
                assertThat(result.getValue(0, 1)).isEqualTo("Apple");
                assertThat(result.getValue(1, 1)).isEqualTo("Banana");
            }
        }

        @Test
        @DisplayName("チェックポイント後にWAL経由で追加データ確認")
        void checkpointThenInsertAndRecover() throws Exception {
            client.createTable("logs",
                    QueryClient.ColumnSpec.intCol("id"),
                    QueryClient.ColumnSpec.varcharCol("msg"));
            client.execute("INSERT INTO logs VALUES (1, 'first')");
            client.checkpoint();
            client.execute("INSERT INTO logs VALUES (2, 'second')");
            client.execute("INSERT INTO logs VALUES (3, 'third')");
            client.close();

            try (QueryClient client2 = new QueryClient(tempDir)) {
                QueryResult result = client2.execute("SELECT * FROM logs ORDER BY id");
                assertThat(result.getRowCount()).isEqualTo(3);
                assertThat(result.getValue(0, 1)).isEqualTo("first");
                assertThat(result.getValue(1, 1)).isEqualTo("second");
                assertThat(result.getValue(2, 1)).isEqualTo("third");
            }
        }
    }

    @Nested
    @DisplayName("QueryResult フォーマット")
    class ResultFormat {

        @Test
        @DisplayName("結果のテーブル形式フォーマット")
        void formatQueryResult() throws Exception {
            client.createTable("t",
                    QueryClient.ColumnSpec.intCol("id"),
                    QueryClient.ColumnSpec.varcharCol("name"));
            client.execute("INSERT INTO t VALUES (1, 'Alice')");
            client.execute("INSERT INTO t VALUES (2, 'Bob')");

            QueryResult result = client.execute("SELECT * FROM t ORDER BY id");
            String formatted = result.format();

            assertThat(formatted).contains("id");
            assertThat(formatted).contains("name");
            assertThat(formatted).contains("Alice");
            assertThat(formatted).contains("Bob");
            assertThat(formatted).contains("2 row(s)");
        }

        @Test
        @DisplayName("UPDATE結果のフォーマット")
        void formatUpdateResult() throws Exception {
            client.createTable("t", QueryClient.ColumnSpec.intCol("id"));
            QueryResult result = client.execute("INSERT INTO t VALUES (1)");
            String formatted = result.format();
            assertThat(formatted).contains("1 row(s) affected");
        }
    }

    @Nested
    @DisplayName("エラーケース")
    class ErrorCases {

        @Test
        @DisplayName("存在しないテーブルへのSELECT")
        void selectFromNonExistentTable() {
            assertThatThrownBy(() -> client.execute("SELECT * FROM nonexistent"))
                    .isInstanceOf(java.sql.SQLException.class);
        }

        @Test
        @DisplayName("存在しないカラムへのSELECT")
        void selectNonExistentColumn() throws Exception {
            client.createTable("t", QueryClient.ColumnSpec.intCol("id"));
            assertThatThrownBy(() -> client.execute("SELECT wrong_col FROM t"))
                    .isInstanceOf(java.sql.SQLException.class);
        }

        @Test
        @DisplayName("型不一致のINSERT")
        void insertTypeMismatch() throws Exception {
            client.createTable("t", QueryClient.ColumnSpec.intCol("id"));
            assertThatThrownBy(() -> client.execute("INSERT INTO t VALUES ('not_a_number')"))
                    .isInstanceOf(Throwable.class);
        }
    }

    @Test
    @DisplayName("executeScript: 複数文の一括実行")
    void executeScriptMultipleStatements() throws Exception {
        client.createTable("products",
                QueryClient.ColumnSpec.intCol("id"),
                QueryClient.ColumnSpec.varcharCol("name"),
                QueryClient.ColumnSpec.doubleCol("price"));

        java.util.List<QueryResult> results = client.executeScript(
                "INSERT INTO products VALUES (1, 'Pen', 1.50)",
                "INSERT INTO products VALUES (2, 'Notebook', 3.00)",
                "INSERT INTO products VALUES (3, 'Eraser', 0.80)",
                "SELECT COUNT(*) AS cnt FROM products",
                "SELECT name FROM products WHERE price > 1.00 ORDER BY price DESC"
        );

        assertThat(results).hasSize(5);
        assertThat(results.get(0).getAffectedRows()).isEqualTo(1);
        assertThat(results.get(1).getAffectedRows()).isEqualTo(1);
        assertThat(results.get(2).getAffectedRows()).isEqualTo(1);

        QueryResult countResult = results.get(3);
        assertThat(((Number) countResult.getValue(0, 0)).intValue()).isEqualTo(3);

        QueryResult filterResult = results.get(4);
        assertThat(filterResult.getRowCount()).isEqualTo(2);
        assertThat(filterResult.getValue(0, 0)).isEqualTo("Notebook");
        assertThat(filterResult.getValue(1, 0)).isEqualTo("Pen");
    }
}

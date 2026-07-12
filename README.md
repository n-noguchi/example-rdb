# Example RDB

Apache Calcite（SQLエンジン）とApache Arrow（列指向ストレージ）を使った学習用シンプルRDBです。データはArrow IPCファイルへ永続化し、更新はWALで保護します。

SELECT時は **WAL+mmap方式（Base+Delta）** で動作します。過去データはArrowファイルを8192行バッチで遅延読込みし、未チェックポイントのINSERTのみメモリに保持するため、データ量がメモリ容量を超えても動作します。

## 必要環境

- Docker / Docker Compose

## クイックスタート

```bash
docker compose build
docker compose run --rm rdb-dev mvn test
```

## JDBCリモート接続（Avatica / DBeaver）

Avaticaサーバーを起動します。

```bash
docker compose up --build rdb-server
```

DBeaverでは「Apache Calcite Avatica」の接続を作成し、次のJDBC URLを設定します。

```text
jdbc:avatica:remote:url=http://localhost:8765;serialization=PROTOBUF
```

JavaのJDBCクライアントからも同じURLで接続できます。

```java
String url = "jdbc:avatica:remote:url=http://localhost:8765;serialization=PROTOBUF";
try (Connection connection = DriverManager.getConnection(url);
     Statement statement = connection.createStatement()) {
    statement.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(100))");
    statement.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");
    try (ResultSet result = statement.executeQuery("SELECT * FROM users")) {
        // 結果を読む
    }
}
```

CLIで起動する場合（Avatica + Flight SQL 統合サーバー）:

```bash
# 統合サーバー（推奨）: Avatica :8765 + Flight SQL :8815
mvn compile exec:java -Dexec.mainClass=com.example.rdb.remote.ExampleRdbServer -Dexec.args="--data-dir ./data --avatica-port 8765 --flight-port 8815"

# Avaticaのみ
mvn compile exec:java -Dexec.mainClass=com.example.rdb.remote.ExampleAvaticaServer -Dexec.args="--data-dir ./data --port 8765"
```

認証・TLSは未実装のため、信頼できるネットワーク内だけで公開してください。

### JDBC DDL

```sql
CREATE TABLE users (
  id INTEGER PRIMARY KEY,
  name VARCHAR(100),
  active BOOLEAN
);

DROP TABLE IF EXISTS users;
```

`CREATE TABLE [IF NOT EXISTS]`、`DROP TABLE [IF EXISTS]`、単一列・複合`PRIMARY KEY`をサポートします。主キーはNULL値と重複値を拒否します。

セカンダリインデックス（Covering Index）も対応しています:

```sql
CREATE INDEX idx_users_email ON users(email) INCLUDE (name, active);
DROP INDEX idx_users_email ON users;
```

詳細は [Covering Index設計書](docs/COVERING_INDEX_DESIGN.md) を参照してください。

## 対応機能

### DDL

| 機能 | 構文 | 備考 |
|------|------|------|
| テーブル作成 | `CREATE TABLE [IF NOT EXISTS] name (col type, ...)` | |
| テーブル削除 | `DROP TABLE [IF EXISTS] name` | Arrowデータファイルも同時削除 |
| 主キー（単一） | `col type PRIMARY KEY` | 列定義に_inline指定_ |
| 主キー（複合） | `PRIMARY KEY (col1, col2)` | テーブル制約として指定 |
| CHECKPOINT | `CHECKPOINT` | Base+Deltaマージ→Arrowファイル書込み→Deltaクリア |
| セカンダリインデックス作成 | `CREATE INDEX name ON table (cols) [INCLUDE (cols)]` | Covering Index |
| セカンダリインデックス削除 | `DROP INDEX [IF EXISTS] name ON table` | |

#### 対応データ型

| SQL型 | エイリアス | 格納型 |
|-------|-----------|--------|
| `INTEGER` | `INT` | 32bit符号付き整数 |
| `BIGINT` | — | 64bit符号付き整数 |
| `VARCHAR` | `CHAR`, `TEXT` | UTF-8可変長文字列 |
| `DOUBLE` | `FLOAT`, `REAL` | 64bit浮動小数点 |
| `BOOLEAN` | `BOOL` | 真偽値 |

### DML

| 機能 | 構文例 | 備考 |
|------|--------|------|
| 単一行INSERT | `INSERT INTO t VALUES (1, 'Alice')` | |
| 複数行INSERT | `INSERT INTO t VALUES (1,'A'), (2,'B')` | 1文で複数VALUES |
| NULL値 | `INSERT INTO t VALUES (1, NULL)` | 全カラムNULL許容 |
| DELETE（条件付き） | `DELETE FROM t WHERE age >= 30` | 影響行数を返却 |
| DELETE（全行） | `DELETE FROM t` | WHERE省略で全件削除 |
| UPDATE（単一カラム） | `UPDATE t SET age = 31 WHERE id = 1` | |
| UPDATE（複数カラム） | `UPDATE t SET name = 'Bob', age = 25 WHERE id = 1` | |
| UPDATE（全行） | `UPDATE t SET age = 0` | WHERE省略で全件更新 |
| UPDATE（NULL指定） | `UPDATE t SET name = NULL WHERE id = 1` | |
| UPDATE（カラム参照） | `UPDATE t SET name = other_col WHERE id = 1` | 同行の別カラムをコピー |

### SELECT

| 機能 | 構文例 |
|------|--------|
| 全件取得 | `SELECT * FROM users` |
| カラム指定 | `SELECT name, age FROM users` |
| WHERE（数値比較） | `WHERE age >= 30` |
| WHERE（AND） | `WHERE age > 20 AND age < 40` |
| WHERE（文字列一致） | `WHERE name = 'Alice'` |
| ORDER BY | `ORDER BY age ASC` / `DESC` |
| LIMIT | `LIMIT 10` |
| DISTINCT | `SELECT DISTINCT age FROM users` |

#### 集計関数

| 関数 | 構文例 |
|------|--------|
| COUNT | `SELECT COUNT(*) FROM users` |
| MAX | `SELECT MAX(age) FROM users` |
| MIN | `SELECT MIN(age) FROM users` |
| SUM | `SELECT SUM(age) FROM users` |
| AVG | `SELECT AVG(age) FROM users` |
| GROUP BY | `SELECT age, COUNT(*) FROM users GROUP BY age` |

#### JOIN

```sql
-- INNER JOIN
SELECT u.name, o.product
FROM users u
INNER JOIN orders o ON u.id = o.uid
WHERE o.amount > 100
ORDER BY o.oid;

-- JOIN + GROUP BY集計
SELECT u.name, SUM(o.amount) AS total
FROM users u
JOIN orders o ON u.id = o.uid
GROUP BY u.name
ORDER BY u.name;
```

### トランザクション

- **AUTOCOMMITのみ対応**。各INSERTは自動的にBEGIN→INSERT→COMMITのWALレコードとして記録される
- 明示的 `BEGIN` / `COMMIT` / `ROLLBACK` は未対応

### 永続化とリカバリ

| 機能 | 説明 |
|------|------|
| WAL（Write-Ahead Logging） | INSERTごとにディスクへ先行書込み。クラッシュ時にデータ消失を防ぐ |
| CHECKPOINT | 手動（`CHECKPOINT`文）または自動（時間間隔）。Base+DeltaをマージしてArrowファイルに永続化 |
| 自動チェックポイント | `startCheckpoint(intervalSeconds)` でバックグラウンド定期実行 |
| クラッシュリカバリ | 再起動時に catalog.json → Arrowファイル(Base) → WAL(Delta) の順で自動復元 |

### ストレージ方式（Base + Delta）

| 項目 | 説明 |
|------|------|
| Base | チェックポイント時点のスナップショット。Arrow IPCファイルをmmap遅延読込み（8192行バッチ） |
| Delta | チェックポイント以降のINSERT行。メモリ上に保持、チェックポイント時にクリア |
| メモリ効率 | 過去データはOSページキャッシュに任せる。メモリに乗るのはDelta（未フラッシュ分）のみ |

### 接続方式

| 接続方式 | 接続先 | 用途 |
|---------|-------|------|
| 組込みJDBC | 同一JVM | `ExampleRdb.getConnection()` |
| リモートJDBC (Avatica) | `jdbc:avatica:remote:url=http://host:8765` | 通常SQL操作、DBeaver対応 |
| リモート (Flight SQL) | `grpc://host:8815` | バルクインポート（erdb-cli） |

### 未対応の機能

- 明示的トランザクション（BEGIN / COMMIT / ROLLBACK）
- 外部キー制約
- CREATE DATABASE / ALTER TABLE
- UPDATEでの算術式（`SET age = age + 1`）
- リモート接続の認証・TLS
- テーブルレベルの排他ロック（`TableLockManager`未実装。`CopyOnWriteArrayList`と`synchronized`により並行安全性は確保）

## ドキュメント

- [設計書](docs/DESIGN.md) — アーキテクチャ全体、データフロー、コンポーネント一覧
- [クエリ経路シーケンス図](docs/SEQUENCE.md) — SELECT/INSERT/DDL/CHECKPOINT/リカバリのMermaidシーケンス図
- [WAL+mmap方式設計書](docs/WAL_MMAP_DESIGN.md) — Base+Delta方式のメモリ管理、制限事項
- [セカンダリインデックス設計書](docs/COVERING_INDEX_DESIGN.md) — Covering Index方式、DML連携、制限事項
- [バルクインポート設計書](docs/BULK_IMPORT.md) — erdb-cli + Arrow Flight SQL、クライアント検証、制限事項

## テストコマンド

```bash
# 全テスト（144件）
docker compose run --rm rdb-dev mvn test

# インデックステストのみ
docker compose run --rm rdb-dev mvn test -Dtest=SecondaryIndexTest

# トランザクション原子性テスト
docker compose run --rm rdb-dev mvn test -Dtest=TransactionAtomicityTest

# UPDATE/DELETEテストのみ
docker compose run --rm rdb-dev mvn test -Dtest=UpdateDeleteTest

# mmap永続化テストのみ
docker compose run --rm rdb-dev mvn test -Dtest=MmapPersistenceTest

# Avaticaを含むリモートJDBCテスト
docker compose run --rm rdb-dev mvn test -Dtest=JdbcQueryClientTest

# 外部JDBCテストクライアントを実行（サーバー起動後）
mvn test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.example.rdb.testclient.AvaticaTestClient
```

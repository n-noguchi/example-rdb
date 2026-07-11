# Example RDB

Apache Calcite（SQLエンジン）とApache Arrow（列指向ストレージ）を使った学習用シンプルRDBです。データはArrow IPCファイルへ永続化し、更新はWALで保護します。

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

CLIで起動する場合:

```bash
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

`CREATE TABLE [IF NOT EXISTS]`、`DROP TABLE [IF EXISTS]`、単一列・複合`PRIMARY KEY`をサポートします。主キーはNULL値と重複値を拒否します。`CREATE DATABASE`、外部キー、インデックスは未対応です。

## ドキュメント

- [設計書](docs/DESIGN.md)

## よく使うコマンド

```bash
# 全テスト
docker compose run --rm rdb-dev mvn test

# Avaticaを含むリモートJDBCテスト
docker compose run --rm rdb-dev mvn test -Dtest=JdbcQueryClientTest

# 外部JDBCテストクライアントを実行（サーバー起動後）
mvn test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.example.rdb.testclient.AvaticaTestClient
```

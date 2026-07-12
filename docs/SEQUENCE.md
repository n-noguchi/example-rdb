# JDBC クエリ経路シーケンス図

JDBC経由で発行されたSQLがどのような経路をたどってデータに到達するかを、DML・DDLごとにMermaidシーケンス図で示す。

## 関連クラスの役割

| クラス | パッケージ | 役割 |
|--------|-----------|------|
| `JdbcDdlSupport` | `jdbc` | JDBC Connection/Statementを動的プロキシでラップ。DDL/CHECKPOINT/DELETE/UPDATE/SELECT(Index)をCalcite到達前にインターセプトする |
| `ExampleRdb` | (root) | エントリポイント。Schema/WAL/Storage/Catalog/Indexを統合し、接続・リカバリ・チェックポイント・インデックス管理を提供 |
| `ExampleSchema` | `schema` | Calcite Schema実装。テーブル名→ExampleTableのマッピングを管理 |
| `ExampleTable` | `schema` | Calcite ScannableTable + ModifiableTable実装。Base(Arrow mmap)+Delta(メモリ)のマージスキャン。deletedRowsによるBase行のフィルタリング。Covering Index連携。WAL連携Collection内蔵 |
| `ArrowBatchEnumerable` | `storage` | Arrow IPCファイルを8192行バッチ単位でmmap遅延読込みするCalcite Enumerable |
| `MergedEnumerable` | `schema` | Base(ArrowBatchEnumerable) + Delta(ListEnumerable)の2つのEnumerableを連結 |
| `IndexManager` | `index` | テーブル単位のセカンダリインデックス管理。Covering Index Scan、DML連携 |
| `CoveringDeltaIndex` | `index` | Delta Index (TreeMap + tombstone + prefix lookup) |
| `CoveringIndexFile` | `index` | Arrow IPC形式のソート済みインデックスファイル読み書き |
| `TransactionManager` | `engine` | AUTOCOMMITのWAL先行書込みを実行 |
| `WalManager` | `wal` | WALセグメントの読み書き・ローテーション。コミット済みTx追跡 |
| `ArrowStorage` | `storage` | Arrow IPCファイルの読み書き（8192行バッチ、マージ書込み） |
| `CatalogManager` | `schema` | テーブル定義（カラム・主キー・インデックス）をJSON永続化 |
| `CheckpointManager` | `wal` | 時間間隔でのチェックポイント実行 |
| `ExampleRdbServer` | `remote` | 統合ランチャー（Avatica :8765 + Flight SQL :8815） |
| `ExampleAvaticaServer` | `remote` | Avatica HTTPプロトコルでリモートJDBC接続を提供 |
| `ExampleJdbcMeta` | `remote` | Avaticaメタデータアダプタ。共有ExampleRdbからConnection生成 |
| `ExampleFlightSqlServer` | `remote` | Arrow Flight SQL gRPCサーバー（バルクインポート用） |
| `ErdbFlightSqlProducer` | `remote` | NoOpFlightSqlProducer継承。acceptPutStatementBulkIngest実装 |
| `ErdbCli` | `cli` | CLIエントリポイント（picocli）。importサブコマンド提供 |

---

## 1. SELECT（参照クエリ）

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client<br/>(JDBC)
    participant Proxy as JdbcDdlSupport<br/>(Statement Proxy)
    participant Calcite as Calcite Engine<br/>(Parse→Validate→Optimize)
    participant Table as ExampleTable
    participant Base as ArrowBatchEnumerable<br/>(Base: Arrow mmap)
    participant Delta as ListEnumerable<br/>(Delta: メモリ)
    participant Disk as Disk<br/>(tables/*.arrow)

    Client->>Proxy: stmt.executeQuery("SELECT * FROM users WHERE age >= 30")
    Proxy->>Proxy: SQLがDDLか判定<br/>(CREATE/DROP/CHECKPOINTに非該当)
    Proxy->>Calcite: executeQuery(sql) を委譲
    Calcite->>Calcite: SQL解析 (SqlNode生成)
    Calcite->>Calcite: バリデーション<br/>(スキーマ・カラム存在確認)
    Note over Calcite: ExampleSchema.getTableMap() から<br/>ExampleTable を取得
    Calcite->>Calcite: リレーショナル代数変換<br/>(Scan + Filter + Project)
    Calcite->>Calcite: 最適化<br/>(EnumerableConverter適用)
    Calcite->>Table: scan(root) 呼び出し
    Table->>Table: MergedEnumerable(base, delta) 生成

    Note over Base: Base: Arrow IPCファイル遅延読込み
    Base->>Base: ArrowFileReader オープン
    Base->>Disk: loadNextBatch() で順次読込<br/>(最大8192行/バッチ)
    Disk-->>Base: VectorSchemaRoot
    Note over Base: アクセスしたページのみ<br/>OSページキャッシュに乗る
    Base->>Base: Object[] 行データ抽出して yield

    Note over Delta: Delta: 未フラッシュ行をメモリから
    Delta->>Delta: deltaRows を反復

    Calcite->>Calcite: Filter適用 (age >= 30)<br/>各rowを列挙しながら評価
    Calcite->>Calcite: Project適用 (SELECT列抽出)
    Base->>Base: Enumerator.close()<br/>ArrowFileReader をクローズ
    Calcite-->>Proxy: ResultSet
    Proxy-->>Client: ResultSet
    Client->>Client: rs.next() / rs.getInt() / rs.getString()
```

### ポイント
- **Base は mmap 遅延読込み**。Arrow IPCファイルから8192行バッチ単位で読み込む。アクセスしたページのみ物理メモリを消費
- **Delta はメモリ上の未フラッシュ行**。チェックポイント後にクリアされるため、サイズは有限
- WHERE / ORDER BY / JOIN / GROUP BY等はCalciteのEnumerable実行エンジンが処理（遅延読込みの恩恵を受ける）
- DDLでないSQLはプロキシを素通りしてCalciteへ

---

## 2. INSERT（データ挿入）

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client<br/>(JDBC)
    participant Proxy as JdbcDdlSupport<br/>(Statement Proxy)
    participant Calcite as Calcite Engine
    participant Table as ExampleTable<br/>(ModifiableTable)
    participant Coll as WalBackedCollection
    participant TxMgr as TransactionManager
    participant WAL as WalManager
    participant Disk as Disk<br/>(WALファイル)
    participant Delta as Delta Buffer<br/>(List<Object[]>)

    Client->>Proxy: stmt.executeUpdate("INSERT INTO users VALUES (1,'Alice',30)")
    Proxy->>Proxy: SQLがDDLか判定<br/>(非該当 → Calciteへ委譲)
    Proxy->>Calcite: executeUpdate(sql)
    Calcite->>Calcite: SQL解析・バリデーション
    Note over Calcite: ExampleTable が ModifiableTable<br/>であることを確認
    Calcite->>Calcite: LogicalTableModify 生成<br/>(toModificationRel 呼び出し)
    Calcite->>Calcite: 最適化<br/>→ EnumerableTableModify へ変換
    Calcite->>Table: getModifiableCollection()
    Table-->>Calcite: WalBackedCollection インスタンス

    Calcite->>Coll: collection.add(row)
    Coll->>Coll: ensureArray(row)<br/>スカラー値をObject[]に変換
    Coll->>Coll: validatePrimaryKey(row)<br/>主キー制約・重複チェック

    Note over Coll,TxMgr: ★ WAL先行書込み（AUTOCOMMIT）
    Coll->>TxMgr: walAware.onInsert(tableName, values)
    TxMgr->>WAL: beginTransaction()
    WAL->>Disk: WALレコード追記: BEGIN {txId}<br/>(flush + fsync)
    WAL-->>TxMgr: txId
    TxMgr->>WAL: logInsert(txId, tableName, values)
    WAL->>Disk: WALレコード追記: INSERT {txId, table, values}<br/>(flush + fsync)
    TxMgr->>WAL: commitTransaction(txId)
    WAL->>Disk: WALレコード追記: COMMIT {txId}<br/>(flush + fsync)
    TxMgr-->>Coll: 完了

    Coll->>Delta: deltaRows.add(row)<br/>Delta Buffer に追加
    Coll-->>Calcite: true (追加成功)
    Calcite-->>Proxy: updateCount = 1
    Proxy-->>Client: 1 (影響行数)
```

### ポイント
- **WAL書込みがDelta追加より先**（先行ログ）。WALのfsync後にDelta更新
- INSERTはDelta Buffer（メモリ）にのみ追加。Base（Arrowファイル）には触れない
- 各INSERTに対し AUTOCOMMIT で BEGIN→INSERT→COMMIT の3レコードがWALに書かれる
- 主キー制約は `WalBackedCollection.add()` 内で検証される（Delta内のみ）
- 単一カラムテーブルの場合、Calciteがスカラー値を渡す可能性があるため `ensureArray()` で変換

---

## 3. DELETE（データ削除）

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client<br/>(JDBC)
    participant Proxy as JdbcDdlSupport<br/>(Statement Proxy)
    participant Calcite as Calcite Engine<br/>(SELECT借用)
    participant Table as ExampleTable
    participant TxMgr as TransactionManager
    participant WAL as WalManager
    participant Disk as Disk

    Client->>Proxy: stmt.executeUpdate("DELETE FROM users WHERE age >= 30")
    Proxy->>Proxy: 正規表現マッチ: DELETE

    Note over Proxy,Calcite: 該当行をSELECT取得
    Proxy->>Calcite: executeQuery("SELECT * FROM users WHERE age >= 30")
    Calcite->>Table: scan(root)
    Table-->>Calcite: MergedEnumerable (Base + Delta)
    Calcite-->>Proxy: ResultSet (該当行)
    Proxy->>Proxy: Object[] 行データを抽出

    Note over Proxy,Table: 各該当行を削除
    loop 各該当行
        Proxy->>Table: deleteRows(rows)
        Table->>Table: removeRowInternal(row)
        alt 行がDeltaにある
            Table->>Table: deltaRows.remove(row)
        else 行がBaseにある
            Table->>Table: deletedRows.add(row) ← tombstone
        end

        Note over Table,TxMgr: ★ WAL先行書込み
        Table->>TxMgr: walAware.onDelete(table, values)
        TxMgr->>WAL: begin → logDelete → commit
        WAL->>Disk: WAL: BEGIN / DELETE / COMMIT
    end

    Proxy-->>Client: 影響行数
```

### ポイント
- **CalciteのSELECTを借用して該当行を特定**。WHERE条件の評価はCalciteに任せる
- Delta行は `deltaRows` から直接削除。Base行は `deletedRows` にtombstoneとして記録
- SELECT時に `FilteredEnumerator` が `deletedRows` をスキップするため、削除されたBase行は結果に現れない
- 各DELETE行に対しWALに DELETE レコードを書き込む（AUTOCOMMIT）

---

## 4. UPDATE（データ更新）

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client<br/>(JDBC)
    participant Proxy as JdbcDdlSupport<br/>(Statement Proxy)
    participant Calcite as Calcite Engine<br/>(SELECT借用)
    participant Table as ExampleTable
    participant TxMgr as TransactionManager
    participant WAL as WalManager
    participant Disk as Disk

    Client->>Proxy: stmt.executeUpdate("UPDATE users SET name='Bob', age=26 WHERE id=2")
    Proxy->>Proxy: 正規表現マッチ: UPDATE
    Proxy->>Proxy: SET句解析: name='Bob', age=26

    Note over Proxy,Calcite: 該当行をSELECT取得
    Proxy->>Calcite: executeQuery("SELECT * FROM users WHERE id=2")
    Calcite->>Table: scan(root)
    Table-->>Calcite: MergedEnumerable (Base + Delta)
    Calcite-->>Proxy: ResultSet (該当行)
    Proxy->>Proxy: oldRows抽出

    Note over Proxy: 新しい行を生成
    loop 各該当行
        Proxy->>Proxy: newRow = oldRow.clone()
        Proxy->>Proxy: SET式を評価してカラム値更新<br/>(リテラル / NULL / カラム参照)
    end

    Note over Proxy,Table: 古い行を削除 + 新しい行を追加
    Proxy->>Table: applyUpdates(oldRows, newRows)
    loop 各行ペア
        Table->>Table: removeRowInternal(oldRow)
        Table->>Table: deltaRows.add(newRow)

        Note over Table,TxMgr: ★ WAL先行書込み (DELETE + INSERT)
        Table->>TxMgr: walAware.onDelete(table, oldValues)
        TxMgr->>WAL: WAL: DELETE oldRow
        Table->>TxMgr: walAware.onInsert(table, newValues)
        TxMgr->>WAL: WAL: INSERT newRow
    end

    Proxy-->>Client: 影響行数
```

### ポイント
- UPDATEは **DELETE + INSERT の組み合わせ**として実装。WALにも両方のレコードが書かれる
- SET式は リテラル値（`'Bob'`, `26`, `NULL`, `TRUE`）とカラム参照（`name = other_col`）に対応
- 算術式（`age = age + 1`）は未対応
- 古い行がBaseにある場合は `deletedRows` にtombstone追加、新しい行は `deltaRows` に追加

---

## 5. CREATE TABLE（DDL）

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client<br/>(JDBC)
    participant Proxy as JdbcDdlSupport<br/>(Statement Proxy)
    participant Ddl as JdbcDdlSupport<br/>(DDLハンドラ)
    participant Rdb as ExampleRdb
    participant Schema as ExampleSchema
    participant Table as ExampleTable
    participant Catalog as CatalogManager
    participant Disk as Disk<br/>(catalog.json)

    Client->>Proxy: stmt.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR)")
    Proxy->>Proxy: StatementHandler.invoke<br/>メソッド名 = "execute"
    Proxy->>Ddl: executeDdl(sql, database)<br/>正規表現マッチ: CREATE_TABLE

    Ddl->>Ddl: SQL解析
    Note over Ddl: 1. テーブル名抽出 (users)<br/>2. IF NOT EXISTS チェック<br/>3. カラム定義パース<br/>4. PRIMARY KEY 制約抽出

    Ddl->>Rdb: getSchema().getExampleTable("users")<br/>既存テーブル確認
    Schema-->>Ddl: null (未存在)

    Ddl->>Rdb: createTable("users", columns, primaryKeyColumns)
    Rdb->>Table: new ExampleTable("users", columns, ["id"])
    Note over Table: 主キーカラムの存在確認<br/>(validatePrimaryKeyColumns)
    Rdb->>Table: setWalAware(transactionManager)
    Rdb->>Schema: addTable(table)
    Rdb->>Catalog: save(allTables)
    Catalog->>Disk: catalog.json 書き込み<br/>{"tables":[{"name":"users","columns":[...],"primaryKey":["id"]}]}

    Ddl-->>Proxy: true (DDL処理済み)
    Proxy->>Proxy: handledDdl = true<br/>戻り値生成: false (ResultSetなし)
    Proxy-->>Client: execute → false
    Client->>Proxy: stmt.getUpdateCount()
    Proxy-->>Client: 0
```

### ポイント
- **Calciteは関与しない**。DDLはJdbcDdlSupportプロキシが正規表現でインターセプト
- テーブル定義は `CatalogManager` 経由で `catalog.json` に永続化
- カラム型は `INT → INTEGER`, `VARCHAR → VARCHAR` 等にマッピング
- `IF NOT EXISTS` が付いていれば既存テーブルを無視（エラーにしない）

---

## 6. DROP TABLE（DDL）

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client<br/>(JDBC)
    participant Proxy as JdbcDdlSupport<br/>(Statement Proxy)
    participant Ddl as JdbcDdlSupport<br/>(DDLハンドラ)
    participant Rdb as ExampleRdb
    participant Schema as ExampleSchema
    participant Catalog as CatalogManager
    participant Disk as Disk

    Client->>Proxy: stmt.execute("DROP TABLE users")
    Proxy->>Ddl: executeDdl(sql, database)<br/>正規表現マッチ: DROP_TABLE

    Ddl->>Ddl: テーブル名抽出 (users)<br/>IF EXISTS チェック
    Ddl->>Rdb: dropTable("users")
    Rdb->>Rdb: getExampleTable("users")<br/>存在確認
    Rdb->>Disk: tables/users.arrow 削除<br/>(Files.deleteIfExists)
    Rdb->>Schema: removeTable("users")
    Rdb->>Catalog: save(残存テーブル一覧)
    Catalog->>Disk: catalog.json 更新
    Rdb-->>Ddl: true (削除成功)

    Ddl-->>Proxy: true (DDL処理済み)
    Proxy-->>Client: execute → false
    Client->>Proxy: stmt.getUpdateCount()
    Proxy-->>Client: 0
```

### ポイント
- Arrowデータファイル（`.arrow`）も同時に削除
- `IF EXISTS` が無く、テーブルが存在しない場合は `SQLException` をスロー

---

## 7. CHECKPOINT

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client<br/>(JDBC)
    participant Proxy as JdbcDdlSupport<br/>(Statement Proxy)
    participant Ddl as JdbcDdlSupport<br/>(DDLハンドラ)
    participant Rdb as ExampleRdb
    participant Storage as ArrowStorage
    participant WAL as WalManager
    participant Table as ExampleTable
    participant Disk as Disk

    Client->>Proxy: stmt.execute("CHECKPOINT")
    Proxy->>Ddl: executeDdl(sql, database)<br/>正規表現マッチ: CHECKPOINT

    Ddl->>Rdb: database.checkpoint()

    loop 各テーブル
        Rdb->>Table: getBaseDataPath(), getDeltaRows(), getColumns()
        Rdb->>Storage: writeMergedTable(arrowFile, basePath, deltaRows, columns)
        Note over Storage: 1. Base(Arrow)全行読込<br/>2. Delta行を追加<br/>3. 8192行バッチで新ファイル書込み<br/>4. atomic rename で置換
        Storage->>Disk: tables/users.arrow (マージ済みデータ)

        Rdb->>Table: setBaseDataPath(arrowFile)
        Rdb->>Table: clearDelta()
        Note over Table: Delta Buffer をクリア<br/>← メモリ解放
    end

    Rdb->>WAL: rotateSegment()
    Note over WAL: 新しいセグメントを作成

    Rdb->>WAL: deleteOldSegments(currentSegment)
    Note over WAL, Disk: チェックポイント済みの<br/>古いWALセグメントを削除

    Ddl-->>Proxy: true
    Proxy-->>Client: execute → false
    Client->>Proxy: stmt.getUpdateCount()
    Proxy-->>Client: 0
```

### ポイント
- **Base + Delta をマージして新Arrowファイル書込み**。従来の「全メモリデータをフラッシュ」から変更
- チェックポイント後にDelta Bufferをクリア → メモリ解放
- Base pathを新ファイルに更新。次回SELECTは新ファイルから遅延読込み
- WALセグメントをローテートし、古いセグメントを削除
- 自動チェックポイントの場合は `CheckpointManager` がスケジューラスレッドから同じ処理を実行

---

## 8. リカバリ（起動時）

```mermaid
sequenceDiagram
    autonumber
    participant Rdb as ExampleRdb<br/>(コンストラクタ)
    participant Catalog as CatalogManager
    participant Table as ExampleTable
    participant WAL as WalManager
    participant Disk as Disk

    Note over Rdb: new ExampleRdb(dataDir)

    Rdb->>Disk: ディレクトリ作成<br/>(data/, wal/, tables/, meta/)
    Rdb->>WAL: new WalManager(walDir/)
    Note over WAL: 既存セグメントをスキャン<br/>最新segment番号 + 1 から継続

    Rdb->>Catalog: load()
    Catalog->>Disk: meta/catalog.json 読み込み
    Catalog-->>Rdb: List<TableDef><br/>[{name, columns, primaryKey}]

    loop 各テーブル定義
        Rdb->>Table: new ExampleTable(name, columns, pk)
        Rdb->>Table: setAllocator(storage.allocator)
        Rdb->>Table: setWalAware(transactionManager)
        Rdb->>Schema: addTable(table)

        Rdb->>Disk: tables/<name>.arrow 存在確認
        alt Arrowファイルが存在
            Rdb->>Table: setBaseDataPath(arrowFile)
            Note over Table: ★ 全件メモリ読込しない<br/>パスを設定するだけ<br/>実際の読込みはSELECT時に<br/>ArrowBatchEnumerable で遅延実行
        end
    end

    Note over Rdb: ★ WAL未フラッシュ分をDeltaに再適用
    Rdb->>WAL: readAllSegments()
    WAL->>Disk: 全WALセグメント読み込み
    WAL-->>Rdb: List<WalRecord>

    loop 各WALレコード
        alt operation == INSERT
            Rdb->>Rdb: normalizeValue()<br/>Long → Integer 等の型変換
            Rdb->>Table: addRow(row) → deltaRows に追加
        else operation == DELETE
            Rdb->>Rdb: normalizeValue()
            Rdb->>Table: deleteRow(row)
            Note over Table: deltaRowsにあれば削除<br/>なければdeletedRowsにtombstone追加
        end
    end

    Note over Rdb: リカバリ完了<br/>Base: ファイルパス参照のみ（遅延読込み）<br/>Delta: WALリプレイ分
```

### ポイント
- **復元順序**: catalog.json → Base path設定 → WAL再適用
- 従来方式はArrowファイルを全件メモリに読み込んでいたが、**mmap方式ではパスを設定するだけ**で全件読込を回避
- Arrowファイルは直近のチェックポイント時点のスナップショット。SELECT時に `ArrowBatchEnumerable` が8192行バッチで遅延読込み
- WALはチェックポイント後に追加された差分のみ残存する（古いセグメントは削除済み）
- WALの数値はJSON経由で `Long` になるため、カラム型に応じて `normalizeValue()` で `Integer` 等に変換

---

## 9. Avaticaリモートサーバー経由の接続

```mermaid
sequenceDiagram
    autonumber
    participant Remote as リモートClient<br/>(jdbc:avatica:remote)
    participant Server as ExampleAvaticaServer<br/>(HTTP Server)
    participant Meta as ExampleJdbcMeta
    participant Rdb as ExampleRdb
    participant Calcite as Calcite Engine
    participant Data as データ層<br/>(Table/WAL/Storage)

    Note over Server: サーバー起動時
    Server->>Rdb: new ExampleRdb(dataDir)<br/>(リカバリ実行)
    Server->>Meta: new ExampleJdbcMeta(database)
    Server->>Server: HttpServer起動<br/>(Avatica + Protobuf)

    Remote->>Server: HTTP POST (Avatica Protobuf)<br/>"OpenConnection"
    Server->>Meta: openConnection()
    Meta->>Rdb: getConnection()
    Note over Rdb: CalciteConnection 生成<br/>SchemaPlus に "rdb" 登録
    Rdb-->>Meta: Connection<br/>(JdbcDdlSupport ラップ付き)
    Meta-->>Server: connectionId
    Server-->>Remote: OpenConnection応答

    Remote->>Server: HTTP POST<br/>"PrepareAndExecute"<br/>SELECT * FROM users
    Server->>Meta: prepareAndExecute(sql)
    Meta->>Calcite: Statement.executeQuery(sql)
    Calcite->>Data: scan / insert / etc.
    Calcite-->>Meta: ResultSet
    Meta-->>Server: Frame (行データ)
    Server-->>Remote: Protobuf応答

    Note over Remote: リモートClient側で<br/>ResultSetとして復元
```

### ポイント
- リモート接続ではAvatica HTTPプロトコル（Protobufシリアライズ）を使用
- `ExampleJdbcMeta` が共有 `ExampleRdb` から接続を生成するため、全リモートクライアントが同一データベースインスタンスにアクセス
- DDL、DELETE、UPDATEも `JdbcDdlSupport` ラップ経由で機能する
- 複数クライアントからの同時アクセスは `CopyOnWriteArrayList` と `synchronized` で並行安全性を確保
- アプリケーション層の楽観的ロックパターン（version列 + WHERE条件）に対応

---

## 経路まとめ

```
SQL文の種類ごとの経路:

SELECT  ──► JdbcDdlSupport(プロキシ) ──► Calcite ──► ExampleTable.scan()
                                                    ├── ArrowBatchEnumerable (Base: mmap遅延読込み)
                                                    │   └── FilteredEnumerator (deletedRowsをスキップ)
                                                    └── ListEnumerable (Delta: メモリ)
                                                    └── MergedEnumerable で連結
SELECT(Index) ──► JdbcDdlSupport(プロキシ) ──► IndexManager.scanCovering() / scanRange()
                                                    ├── CoveringDeltaIndex (Delta: TreeMap)
                                                    └── CoveringIndexFile (Base: Arrow sorted)
INSERT  ──► JdbcDdlSupport(プロキシ) ──► Calcite ──► ModifiableTable.add() ──► WAL書込み ──► deltaRows + Index更新
DELETE  ──► JdbcDdlSupport(プロキシ) ──► Calcite(SELECT借用) ──► 該当行取得
              ──► table.deleteRows() ──► WAL DELETE書込み ──► deltaRows削除 / deletedRows追加 / Index更新
UPDATE  ──► JdbcDdlSupport(プロキシ) ──► Calcite(SELECT借用) ──► 該当行取得
              ──► SET式評価 ──► table.applyUpdates(old,new)
              ──► WAL DELETE+INSERT書込み ──► deletedRows + deltaRows + Index更新
CREATE  ──► JdbcDdlSupport(プロキシ) ──► ExampleRdb.createTable() ──► CatalogManager ──► catalog.json
DROP    ──► JdbcDdlSupport(プロキシ) ──► ExampleRdb.dropTable() ──► CatalogManager ──► catalog.json
CREATE IDX ──► JdbcDdlSupport(プロキシ) ──► ExampleRdb.createIndex() ──► IndexManager + catalog.json
CKPT    ──► JdbcDdlSupport(プロキシ) ──► writeMergedTable(Base+Delta) ──► clearDelta ──► Index再構築 ──► WAL削除
RECOVER ──► ExampleRdb(コンストラクタ) ──► Catalog読込 ──► setBaseDataPath ──► WAL→Delta/Deleted再適用(コミット済Txのみ)
BULK    ──► Flight SQL gRPC ──► ErdbFlightSqlProducer ──► acceptPutStatementBulkIngest ──► table.addRow() × N
REMOTE  ──► Avatica HTTP ──► ExampleJdbcMeta ──► ExampleRdb.getConnection() ──► (上記いずれか)
```

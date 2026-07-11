# Example RDB 設計書

Apache Calcite + Apache Arrow を用いた学習用シンプルRDBの設計。

## 1. 目標

- **学習目的**: RDBの内部構造（SQL解析・最適化・ストレージ・WAL・リカバリ）を理解する
- **実用的な構成**: 実際のRDBと同じ設計パターンを採用
- **シンプルな実装**: 複雑すぎず、各レイヤが読み解ける規模

---

## 2. 全体アーキテクチャ

```mermaid
graph TB
    Client[組込み JDBC Client]
    RemoteClient[リモート JDBC Client<br/>DBeaver / Avatica JDBC]
    Avatica[Avatica HTTP Server<br/>Protobuf]

    subgraph JDBC["Calcite JDBC Adapter"]
        Parser["SQL Parser<br/>(SQL → SqlNode)"]
        Validator["Validator<br/>(SqlNode → RelNode)"]
        Optimizer["Optimizer<br/>(ルールベース最適化)"]
    end

    subgraph Exec["Query Executor (自作)"]
        Planner["Physical Plan<br/>(RelNode → Enumerable)"]
        Scan[Table Scan]
        Filter[Filter]
        Project[Project]
    end

    subgraph Storage["Storage Engine (自作)"]
        ArrowReader["Arrow Storage<br/>Arrow IPC 読込み"]
        Catalog["Catalog Manager<br/>スキーマ・テーブル定義"]
    end

    subgraph WAL["WAL Manager (自作)"]
        WalWriter["WAL Writer<br/>redoログ先行書込み"]
        Checkpoint["Checkpoint<br/>時間間隔でArrowフラッシュ"]
        Recovery["Recovery<br/>起動時WAL再適用"]
    end

    Disk[(ディスク<br/>data/)]

    Client -->|"JDBC (同一JVM)"| Parser
    RemoteClient -->|"HTTP / Avatica"| Avatica
    Avatica -->|"JDBC"| Parser
    Parser --> Validator
    Validator --> Optimizer
    Optimizer --> Planner
    Planner --> Scan
    Scan --> Filter
    Filter --> Project
    Scan --> ArrowReader
    ArrowReader --> Catalog
    Planner -->|"INSERT/UPDATE/DELETE"| WalWriter
    WalWriter --> Checkpoint
    Checkpoint --> ArrowReader
    WalWriter --> Recovery
    ArrowReader --> Disk
    WalWriter --> Disk
```

---

## 3. コンポーネント一覧

| コンポーネント | 役割 | 技術 | 実装 |
|---|---|---|---|
| Calcite JDBC Adapter | JDBCインターフェース、SQL解析・最適化 | Apache Calcite + Avatica | 既存 |
| Schema / Table | CalciteのSPI実装、メタデータ提供 | Calcite SPI | 自作 |
| Query Executor | RelNode → 物理実行、読み取り処理 | Calcite Enumerable | 自作 |
| Arrow Storage Engine | Arrow IPC形式の読み書き | Apache Arrow Java | 自作 |
| WAL Manager | 書込みの先行ログ化、チェックポイント、リカバリ | 独自実装 | 自作 |
| Catalog Manager | テーブル/カラム定義の永続化 | JSON | 自作 |
| Transaction Manager | トランザクション管理（AUTOCOMMIT中心） | 独自実装 | 自作 |
| Avatica HTTP Server | リモートJDBC要求をCalcite JDBCへ中継 | Apache Avatica Jetty | 自作 |

---

## 4. データフロー

### 4.0 リモート接続フロー（Avatica）

Avaticaサーバーは単一の `ExampleRdb` インスタンスを保持し、各リモートJDBC接続へ
同一の `rdb` スキーマを公開する。データディレクトリはサーバープロセスだけが開く。

```mermaid
sequenceDiagram
    participant DBeaver as DBeaver
    participant Driver as Avatica JDBC Driver
    participant Server as ExampleAvaticaServer
    participant Meta as JDBC Meta
    participant DB as ExampleRdb

    DBeaver->>Driver: jdbc:avatica:remote:url=http://host:8765
    Driver->>Server: HTTP POST (Protobuf)
    Server->>Meta: JDBC request
    Meta->>DB: Calcite JDBC connection (schema=rdb)
    DB-->>Meta: ResultSet / update count
    Meta-->>Server: Avatica response
    Server-->>Driver: HTTP response
    Driver-->>DBeaver: JDBC ResultSet
```

接続先は `http://<host>:8765`、シリアライゼーションは Protobuf を使用する。認証・TLSは
この初期実装の対象外であり、信頼できるネットワーク内でのみ公開する。

---

### 4.1 書込みフロー（WAL方式）

```mermaid
sequenceDiagram
    participant Client
    participant Calcite as Calcite JDBC
    participant TxMgr as Transaction Manager
    participant WAL as WAL Manager
    participant Mem as Arrow Buffer (メモリ)
    participant CKPT as Checkpoint
    participant Disk as Disk

    Client->>Calcite: INSERT INTO t VALUES ...
    Calcite->>TxMgr: BEGIN (AUTOCOMMIT)

    Note over WAL: ★ 先行書込み（fsync）
    WAL->>Disk: 1. WALレコード追記 [txId, INSERT, table, rows]
    WAL-->>TxMgr: ACK

    Mem->>Mem: 2. Arrow Bufferに適用 (VectorSchemaRoot)

    TxMgr->>WAL: 3. COMMIT レコード追記
    WAL->>Disk: COMMIT [txId]

    Note over CKPT: 一定時間経過後
    CKPT->>Mem: 4. Arrow Buffer取得
    CKPT->>Disk: 5. Arrow IPCファイル書込み (atomic rename)
    CKPT->>Disk: 6. 古いWALセグメント削除
```

### 4.2 読込みフロー

```mermaid
sequenceDiagram
    participant Client
    participant Calcite as Calcite JDBC
    participant Exec as Query Executor
    participant Storage as Arrow Storage
    participant WAL as WAL Buffer
    participant Disk as Disk

    Client->>Calcite: SELECT ... FROM t WHERE ...
    Calcite->>Exec: RelNode (Scan + Filter + Project)

    Exec->>Storage: Arrow IPC読込み
    Storage->>Disk: ファイル読出し
    Disk-->>Storage: Arrow RecordBatch
    Storage-->>Exec: VectorSchemaRoot

    Note over Exec,WAL: WAL未フラッシュ分をマージ
    Exec->>WAL: 未コミット/未フラッシュバッファ参照
    WAL-->>Exec: 差分行データ

    Exec->>Exec: Filter / Project 適用
    Exec-->>Calcite: Enumerable<Row>
    Calcite-->>Client: ResultSet
```

### 4.3 リカバリフロー（起動時）

```mermaid
sequenceDiagram
    participant DB as ExampleRDB
    participant Storage as Arrow Storage
    participant WAL as WAL Reader
    participant Disk as Disk

    DB->>Storage: 最新Arrowファイル読込み
    Storage->>Disk: tables/*.arrow
    Disk-->>Storage: VectorSchemaRoot

    DB->>WAL: 未チェックポイントWAL読込み
    WAL->>Disk: WAL/wal_XXX.log
    Disk-->>WAL: WALレコード群

    loop 各WALレコード
        WAL->>Storage: レコード再適用
        Note over WAL: COMMIT済みのみ適用<br/>未完了Txは破棄
    end

    Note over DB: メモリ上に最新状態が復元完了
```

---

## 5. WALレコード構成

```mermaid
graph LR
    subgraph Record["WAL Record (1エントリ)"]
        LSN["LSN<br/>8 bytes"]
        TxId["TxId<br/>4 bytes"]
        Op["Op<br/>1 byte"]
        TLen["TableLen<br/>2 bytes"]
        TName["TableName<br/>可変長"]
        Payload["Payload<br/>Arrow IPC<br/>RecordBatch"]
        CRC["CRC32<br/>4 bytes"]
    end

    LSN --> TxId --> Op --> TLen --> TName --> Payload --> CRC
```

| フィールド | サイズ | 説明 |
|---|---|---|
| LSN | 8 bytes | Log Sequence Number（単調増加） |
| TxId | 4 bytes | トランザクションID |
| Op | 1 byte | `BEGIN(0)` `INSERT(1)` `UPDATE(2)` `DELETE(3)` `COMMIT(4)` `ABORT(5)` |
| TableLen | 2 bytes | テーブル名のバイト長 |
| TableName | 可変長 | 対象テーブル名（UTF-8） |
| Payload | 可変長 | 行データ。Arrow IPC RecordBatchシリアライズ |
| CRC32 | 4 bytes | レコード全体の整合性チェック |

---

## 6. チェックポイント方式

時間間隔トリガー（デフォルト30秒）で実行。

```mermaid
flowchart TD
    Start([チェックポイント開始]) --> Lock[テーブル書き込みロック取得]
    Lock --> Snapshot[Arrow Buffer のスナップショット取得]
    Snapshot --> WriteTemp[一時ファイルにArrow IPC書込み]
    WriteTemp --> Rename[atomic rename で本ファイル置換]
    Rename --> WriteMeta[チェックポイントLSNをメタに記録]
    WriteMeta --> RotateWAL[古いWALセグメントを削除]
    RotateWAL --> Unlock[ロック解放]
    Unlock --> Done([完了])

    style Start fill:#4CAF50,color:#fff
    style Done fill:#4CAF50,color:#fff
    style Rename fill:#FF9800,color:#fff
```

---

## 7. ディスクレイアウト

```
data/
├── meta/
│   └── catalog.json              ← カタログ（テーブル/スキーマ定義）
├── tables/
│   ├── users.arrow               ← Arrow IPC ファイル（テーブル毎）
│   └── orders.arrow
└── wal/
    ├── wal_000001.log            ← WALセグメント
    ├── wal_000002.log
    └── checkpoint.meta           ← 最終チェックポイント情報
```

---

## 8. ディレクトリ構成（プロジェクト）

```
example-rdb/
├── pom.xml
├── docs/
│   └── DESIGN.md
├── src/main/java/com/example/rdb/
│   ├── ExampleRdb.java                  ← エントリポイント
│   │
│   ├── jdbc/
│   │   ├── ExampleDriver.java           ← JDBCドライバ登録
│   │   └── ExampleJdbcFactory.java      ← Calcite JDBC Factory
│   │
│   ├── schema/
│   │   ├── ExampleSchema.java           ← Calcite Schema 実装
│   │   ├── ExampleTable.java            ← Calcite Table 実装
│   │   └── CatalogManager.java          ← カタログ管理
│   │
│   ├── storage/
│   │   ├── ArrowStorage.java            ← Arrow IPC 読み書き
│   │   └── ArrowSchemaConverter.java    ← Arrow ⇔ RelDataType 変換
│   │
│   ├── wal/
│   │   ├── WalManager.java              ← WAL統合管理
│   │   ├── WalWriter.java               ← WAL書込み
│   │   ├── WalReader.java               ← WAL読込み
│   │   ├── WalRecord.java               ← レコード定義
│   │   └── CheckpointManager.java       ← チェックポイント管理
│   │
│   ├── engine/
│   │   ├── QueryExecutor.java           ← 実行エンジン
│   │   └── TransactionManager.java      ← トランザクション管理
│   │
│   └── util/
│       └── FileUtils.java
│
├── src/test/java/com/example/rdb/
│   ├── jdbc/
│   ├── storage/
│   ├── wal/
│   └── engine/
│
└── data/                                ← 実行時に生成（gitignore対象）
```

---

## 9. 技術スタック

| 項目 | 選択 | バージョン(目安) |
|---|---|---|
| 言語 | Java | 17 |
| ビルド | Maven | 3.9+ |
| SQLエンジン | Apache Calcite | 1.37.x |
| JDBCプロトコル | Apache Avatica | 1.23.x |
| 列指向フォーマット | Apache Arrow Java | 15.x |
| テスト | JUnit 5 | 5.10.x |

---

## 10. トランザクション設計

現段階では **AUTOCOMMIT中心**。将来的な明示的トランザクション拡張も見据えた設計。

```mermaid
state diagram-v2
    [*] --> Idle

    Idle --> Active: SQL受信 (AUTOCOMMIT)
    Active --> Writing: WAL先行書込み
    Writing --> Applying: Arrow Buffer適用
    Applying --> Committing: COMMIT
    Committing --> Flushed: COMMIT レコード追記
    Flushed --> Idle: レスポンス返却

    note right of Flushed
        チェックポイントは非同期
        （時間間隔で別スレッド実行）
    end note
```

---

## 11. 実装フェーズ

```mermaid
graph LR
    P1["Phase 1<br/>インメモリ読出し<br/>Calcite + Schema/Table"]
    P2["Phase 2<br/>WAL書込み<br/>WAL Manager + Tx"]
    P3["Phase 3<br/>Arrow永続化<br/>ArrowStorage + Checkpoint"]
    P4["Phase 4<br/>リカバリ<br/>起動時WAL再適用"]

    P1 --> P2 --> P3 --> P4

    style P1 fill:#2196F3,color:#fff
    style P2 fill:#4CAF50,color:#fff
    style P3 fill:#FF9800,color:#fff
    style P4 fill:#9C27B0,color:#fff
```

| Phase | 内容 | 完了条件 |
|---|---|---|
| **1** | Calcite接続、Schema/Table実装、インメモリでSELECT | JDBC経由でSELECTが実行できる |
| **2** | WAL Manager実装、INSERT/UPDATE/DELETEのWAL書込み | データ更新がWALに記録される |
| **3** | Arrow Storage + Checkpoint実装 | データがArrow IPCファイルに永続化される |
| **4** | リカバリ実装 | 再起動後にデータが復元される |

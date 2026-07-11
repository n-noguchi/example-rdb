# セカンダリインデックス（Covering Index）設計書

## 概要

Example RDBのセカンダリインデックスは、Apache Arrow IPCファイルをソート済みインデックスとして活用するCovering Index方式を採用している。Index Only Scanにより、テーブル本体を読まずにクエリ結果を返すことができる。

## アーキテクチャ

```
┌─────────────────────────────────────────────────────────┐
│                    クエリ実行                             │
│                                                         │
│  SELECT name FROM users WHERE email = 'alice@ex.com'   │
│                                                         │
│  ┌─────────────────────────────────────────────┐       │
│  │  JdbcDdlSupport (SELECTインターセプト)        │       │
│  │                                             │       │
│  │  1. 正規表現でSELECT構文解析                  │       │
│  │  2. インデックス候補を検索                    │       │
│  │  3. カバー判定（key + INCLUDE列で充足？）     │       │
│  │                                             │       │
│  │  ├── YES → Index Scan実行                   │       │
│  │  └── NO  → Calcite Full Scanへ委譲           │       │
│  └─────────────────────────────────────────────┘       │
│                         │                               │
│    ┌────────────────────┼────────────────────┐         │
│    │                     │                    │         │
│    ▼                     ▼                    ▼         │
│  ┌──────────┐   ┌──────────────┐   ┌──────────────┐    │
│  │ Base     │   │ Delta        │   │ Tombstones   │    │
│  │ Arrow    │   │ TreeMap      │   │ Set<Long>    │    │
│  │ (sorted) │   │ (in-memory)  │   │              │    │
│  └──────────┘   └──────────────┘   └──────────────┘    │
│                                                         │
│  結果マージ → 一時テーブル → Calcite経由でResultSet返却  │
└─────────────────────────────────────────────────────────┘
```

## インデックス構造

### Base Index (Arrow IPC)

チェックポイント時に全データから構築される、ソート済みのArrow IPCファイル。

```
indexes/users/idx_email.arrow

Arrow Schema:
  email       Utf8        ← キー列
  name        Utf8        ← INCLUDE列
  active      Bool        ← INCLUDE列
  __row_id    Int64       ← 内部行ID

ソート順: email ASC, __row_id ASC
バッチサイズ: 8192行/RecordBatch
```

### Delta Index (in-memory)

チェックポイント後のINSERT/UPDATE/DELETEを保持するメモリ上のTreeMap。

```java
ConcurrentSkipListMap<IndexKey, List<CoveringEntry>> entries
Set<Long> tombstones         // 削除されたBase行のrowId
Set<Long> overriddenBaseRows // UPDATEで上書きされたBase行のrowId
```

### 検索時のマージ

```
結果 = (Base Indexから該当キー範囲をスキャン - tombstones)
     + (Delta Indexから該当キーを検索)
```

## DDL

### CREATE INDEX

```sql
CREATE INDEX index_name
ON table_name (key_column [, ...])
[INCLUDE (include_column [, ...])];
```

例:
```sql
CREATE INDEX idx_users_email ON users(email) INCLUDE (name, active);
CREATE INDEX idx_orders_cust_date ON orders(customer_id, order_date) INCLUDE (amount);
```

### DROP INDEX

```sql
DROP INDEX [IF EXISTS] index_name ON table_name;
```

## クエリパターン

### Covering Index Scan（等価検索）

```sql
-- idx_email(email INCLUDE name, age, active)が利用可能
SELECT name, age FROM users WHERE email = 'alice@example.com';
```

条件:
- WHERE句の列がインデックスの先頭キー列と一致
- SELECTする全列がキー列 + INCLUDE列に含まれる

### Covering Index Scan（範囲検索）

```sql
-- idx_age(age INCLUDE name)が利用可能
SELECT name FROM users WHERE age >= 30;
SELECT name FROM users WHERE age >= 25 AND age <= 35;
```

### 複合インデックスの前方一致

```sql
-- idx_orders_cust_date(customer_id, order_date INCLUDE amount)
SELECT amount FROM orders WHERE customer_id = 100;
-- 第1キー列での等価検索 → 全order_dateをカバー
```

### Full Scan フォールバック

以下の場合はCalciteのFull Scanに委譲する:
- WHERE句の列がインデックスキーの先頭にない
- SELECT列がインデックスでカバーされていない
- 複雑なクエリ（JOIN、サブクエリ、集計等）
- インデックスが未定義

## 内部行ID (__row_id)

各行に `AtomicLong` で生成される永続的な行IDを付与する。

役割:
- Base/Delta間の行同一性判定
- DELETE/UPDATE時のターゲット特定
- TombstoneによるBase行の無効化

SQLユーザーには非公開。テーブルのSQL可視スキーマには含まれない。

## DML連携

### INSERT

```
1. rowId発行 (AtomicLong)
2. WAL先行書込み
3. テーブルdeltaRowsに追加
4. 全インデックスのDeltaに追加
```

### DELETE

```
1. 該当行を特定（SELECT借用）
2. WAL削除レコード書込み
3. テーブル: deltaRowsから削除 or deletedRowsにtombstone追加
4. 全インデックス: tombstone追加、Deltaから削除
```

### UPDATE

```
1. 該当行を特定（SELECT借用）
2. 新しい行データを生成（SET式評価）
3. WAL: DELETE old + INSERT new
4. テーブル: 古い行削除 + 新しい行追加（同一rowId）
5. 全インデックス: Delta内で古いエントリ削除 + 新しいエントリ追加
```

## チェックポイント連携

チェックポイント時に全インデックスを再構築する:

```
1. テーブルの全データ（Base + Delta - Tombstones）を取得
2. 各インデックスについて:
   a. 全エントリをキー順にソート
   b. ソート済みArrow IPCファイルを書込み（atomic rename）
   c. Delta Indexをクリア
3. WALセグメントをローテート・削除
```

## リカバリ

```
1. catalog.jsonからインデックス定義を復元
2. IndexManager.registerIndex() で定義を登録
3. Base Index Arrowファイルを確認（チェックポイント時に作成済み）
4. WALをリプレイ → Delta IndexにINSERT/DELETEを反映
```

## ファイル構成

```
data/
├── tables/
│   └── users.arrow                    ← テーブルデータ
├── indexes/
│   └── users/
│       └── idx_email.arrow            ← ソート済みインデックス
├── meta/
│   └── catalog.json                   ← インデックス定義を含むカタログ
└── wal/
```

## クラス構成

```
src/main/java/com/example/rdb/
├── index/
│   ├── IndexDefinition.java           ← インデックス定義レコード
│   ├── IndexKey.java                  ← 型安全な比較可能キー
│   ├── CoveringEntry.java             ← rowId + キー値 + INCLUDE値
│   ├── CoveringDeltaIndex.java        ← TreeMap + tombstones
│   ├── CoveringIndexFile.java         ← Arrow IPC読み書き
│   └── IndexManager.java              ← テーブル単位のインデックス管理
├── jdbc/
│   └── JdbcDdlSupport.java            ← CREATE/DROP INDEX + SELECTインターセプト
├── schema/
│   ├── ExampleTable.java             ← IndexManager連携、rowId管理
│   └── CatalogManager.java           ← インデックス定義の永続化
└── ExampleRdb.java                    ← createIndex/dropIndex、リカバリ
```

## 対応する検索条件

| 条件 | 対応 | 備考 |
|------|------|------|
| `= value` | ✓ | 等価検索 |
| `> value` | ✓ | 範囲検索 |
| `>= value` | ✓ | 範囲検索 |
| `< value` | ✓ | 範囲検索 |
| `<= value` | ✓ | 範囲検索 |
| `>= v1 AND <= v2` | ✓ | 範囲検索（同一列） |
| `IS NULL` | ✓ | NULL検索 |
| `AND`（複合キー先頭一致） | ✓ | 複合インデックス |

## 未対応（今後の拡張候補）

| 機能 | 理由 |
|------|------|
| OR条件のIndex Union | 初期スコープ外 |
| LIKE前方一致 | 正規表現インターセプトでは困難 |
| 関数インデックス | 構文解析が複雑 |
| Calcite TranslatableTable統合 | 最適化器レベルの統合は将来課題 |
| Fence Directory | バッチレベルmin/maxによる高速バッチ特定 |
| 非Covering Index + Table Lookup | Index Only Scan限定 |
| ORDER BY Sort除去 | インデックス順序の活用 |
| JOIN Index Nested Loop | 複雑なプラン生成が必要 |
| UNIQUE制約 | 主キーとの統合が必要 |

## 既知の制限事項

| 制限 | 説明 |
|------|------|
| SELECTインターセプト方式 | Calcite最適化器を経由しないため、JOIN/サブクエリ内ではインデックスが使用されない |
| SELECT * はインデックス未使用 | 列リストの特定が必要なため、`*`はCalcite Full Scanに委譲 |
| 範囲スキャンの全件読み込み | Fence Directory未実装のため、Base Arrowファイルを全バッチ読み込み後にフィルタ |
| 主キー検証はDeltaのみ | Base内の既存PKとの重複は検出されない |

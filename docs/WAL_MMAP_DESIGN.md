# WAL + mmap 方式 設計書

## 概要

従来の「全データをメモリに保持」する方式から、「Arrow IPCファイルを mmap で遅延読込みする Base + Delta 方式」へ移行する。これによりメモリ容量を超えるデータサイズでも動作可能になる。

## アーキテクチャ

```
┌─────────────────────────────────────────────────┐
│                 scan() が返す行                  │
│                                                 │
│  ┌────────────────────┐  ┌───────────────────┐ │
│  │  Base (読専)        │  │  Delta (可変)      │ │
│  │                    │  │                   │ │
│  │  tables/users.arrow│  │  List<Object[]>   │ │
│  │  mmap 遅延読込み    │  │  未フラッシュ行    │ │
│  │                    │  │                   │ │
│  │  バッチ単位で読込   │  │  CHECKPOINT時に   │ │
│  │  OSページキャッシュ │  │  Baseへ統合→クリア │ │
│  │  に任せる           │  │                   │ │
│  └────────────────────┘  └───────────────────┘ │
└─────────────────────────────────────────────────┘
```

## メモリ管理

### Base の読込み

Arrow IPCファイルを `ArrowFileReader` で開き、`loadNextBatch()` で1バッチ（最大8192行）ずつ読み込む。アクセスしたページのみがOSのページキャッシュに乗る。

| 項目 | 説明 |
|------|------|
| 仮想メモリ解放 | `Enumerator.close()` → `ArrowFileReader.close()` → `munmap` |
| 物理メモリ解放 | OSがメモリ圧力に応じてページキャッシュからLRU追い出し |
| 大量データ時の挙動 | アクセスしたバッチ分のみ物理メモリ消費。100GBファイルでも動作可能 |

### Delta のライフサイクル

```
INSERT ──► Delta追加（WAL先行書込み）
                    │
    CHECKPOINT ─────┘
         │
         ├──► Base + Delta をマージして新Arrowファイル書込み
         ├──► Delta クリア ← メモリ解放
         └──► WALセグメント削除
```

Deltaサイズは前回チェックポイントからのINSERT量で決まり、チェックポイント間隔（デフォルト30秒）で上限が抑えられる。

## 操作ごとのデータフロー

### SELECT

```
scan()
  │
  ├──► 1. ArrowBatchEnumerable
  │        ArrowFileReader.loadNextBatch() で順次読込み
  │        バッチごとに Object[] を抽出して yield
  │        Enumerator.close() で Reader を閉じる
  │
  └──► 2. ListEnumerable(deltaRows)
           Delta の行を yield
```

CalciteのFilter/Project/Sortは全てEnumerable上で動作するため、遅延読込みの恩恵を受ける。

### INSERT

```
WalBackedCollection.add(row)
  │
  ├──► WAL先行書込み (BEGIN → INSERT → COMMIT)
  └──► deltaRows.add(row)
```

Baseには触れない。Deltaのみ更新。

### CHECKPOINT

```
checkpoint()
  │
  loop 各テーブル:
  │   ├──► writeMergedTable(arrowFile, basePath, deltaRows, columns)
  │   │        Base全行読込 + Delta追加 → 新Arrowファイル（バッチ書込み）
  │   │        atomic rename で置換
  │   │
  │   ├──► table.setBaseDataPath(arrowFile)
  │   └──► table.clearDelta()
  │
  ├──► WALセグメントローテート
  └──► 古いWALセグメント削除
```

チェックポイント時はBase全行を読み込むため一時的にメモリ使用量が増えるが、バックグラウンドスレッドで実行される。

### リカバリ（起動時）

```
new ExampleRdb(dataDir)
  │
  ├──► Catalog読込み → テーブル定義復元
  │
  loop 各テーブル:
  │   └──► table.setBaseDataPath(arrowFile)
  │         ※全行メモリ展開しない。パスを設定するだけ
  │
  └──► WAL再適用 → deltaRows に行追加
        ※未チェックポイント分のみ（通常少量）
```

従来方式はArrowファイル全件をメモリに読み込んでいた。mmap方式ではファイルパスを設定するだけで、実際の読込みはSELECT時に遅延発生する。

## Arrow IPCファイルのバッチ構成

チェックポイント書込み時、行を8192行単位のバッチに分割して書き込む。

```
Arrow IPCファイル構造:
┌──────────────────┐
│ Magic (ARROW1)   │
│ Schema           │
├──────────────────┤
│ RecordBatch 0    │  ← 最大8192行
│ RecordBatch 1    │  ← 最大8192行
│ ...              │
│ RecordBatch N    │
├──────────────────┤
│ Footer           │
│ Magic (ARROW1)   │
└──────────────────┘
```

SELECT時、`loadNextBatch()` は1バッチ（最大8192行）のみメモリにロードする。

## 既知の制限事項

| 制限 | 説明 |
|------|------|
| 主キー検証の範囲 | Delta内のみ検証。Base内の既存PKとの重複は検出されない |
| チェックポイント時のメモリ | Base全行を一時的に読み込む（バックグラウンド実行で緩和） |
| 同時チェックポイント | チェックポイント進行中のSELECTは旧ファイルを参照し続ける（Linuxでは安全） |
| UPDATEの算術式 | `SET age = age + 1` 等の式は未対応。リテラル・NULL・カラム参照のみ |
| DELETE/UPDATEのBase行検索 | Calcite SELECTで該当行を全件スキャンするため、大量データ時は低速 |
| deletedRowsの線形探索 | Base行の削除判定がO(n)。大量のtombstoneがある場合はスキャン性能に影響 |

### 並行安全性

`deltaRows`/`deletedRows` は `CopyOnWriteArrayList` でスレッドセーフ。`WalBackedCollection.add()/remove()` と各DMLメソッドは `synchronized` で check-then-act 原子性を保証。負荷テスト（5VU × 3分）で `ConcurrentModificationException` ゼロ件を確認済み。

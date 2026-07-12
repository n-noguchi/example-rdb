# バルクインポート機能（erdb-cli + Arrow Flight SQL）

CSV/TSVデータを Arrow Flight SQL 経由で Example RDBへ一括インポートする機能。

## アーキテクチャ

```
┌─────────────────────────────────────────────────┐
│              リモートクライアント                   │
│                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ CSV/TSV  │→ │ 検証     │→ │ Arrow Vector │  │
│  │ 読込み   │  │ 全件検証  │  │ 構築         │  │
│  └──────────┘  └──────────┘  └──────┬───────┘  │
│                                      │          │
│                ┌─────────────────────┘          │
│                ▼                                │
│  ┌──────────────────────┐                       │
│  │ FlightSqlClient      │                       │
│  │ .executeIngest()     │                       │
│  └──────────┬───────────┘                       │
│             │ gRPC                               │
└─────────────┼───────────────────────────────────┘
              │
┌─────────────┼───────────────────────────────────┐
│  ┌──────────▼───────────┐  Example RDB Server   │
│  │ Flight SQL :8815     │                       │
│  │ ErdbFlightSqlProducer│                       │
│  │ acceptPutStatement   │                       │
│  │   BulkIngest()       │                       │
│  └──────────┬───────────┘                       │
│             │                                    │
│  ┌──────────▼───────────┐                       │
│  │ ExampleTable         │                       │
│  │ .addRow() × N        │                       │
│  └──────────────────────┘                       │
│                                                 │
│  ┌──────────────────────┐                       │
│  │ Avatica :8765        │ (メタデータ取得用)      │
│  └──────────────────────┘                       │
└─────────────────────────────────────────────────┘
```

## メタデータ取得

CLIはJDBC (Avatica) 経由でテーブルスキーマを取得し、Flight SQL経由でデータを送信する。

```
1. JDBC接続 → SELECT * FROM table WHERE 1=0 → 列名・型を取得
2. CSV読込み → 型検証 → Arrow VectorSchemaRoot構築
3. Flight SQL接続 → executeIngest() → サーバーがバッチ受信
```

## 使い方

### サーバー起動

```bash
# ルートディレクトリで
docker compose up -d rdb-server

# 両サーバーが起動:
#   Avatica: http://localhost:8765 (JDBC用)
#   Flight:  grpc://localhost:8815 (バルクインポート用)
```

### データ準備

```csv
# users.csv
id,name,age,active
1,Alice,30,true
2,Bob,25,false
3,Charlie,35,true
```

### インポート実行

```bash
# Docker内で実行
docker compose run --rm rdb-dev java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp target/erdb-cli-0.2.0-SNAPSHOT.jar \
  com.example.rdb.cli.ErdbCli import \
    --endpoint grpc://rdb-server:8815 \
    --table users \
    --file users.csv \
    --format csv
```

### 検証のみ

```bash
# データを送信せず検証だけ実行
erdb-cli import \
  --endpoint grpc://rdb-server:8815 \
  --table users \
  --file users.csv \
  --validate-only
```

### 標準入力から

```bash
cat users.csv | erdb-cli import \
  --endpoint grpc://rdb-server:8815 \
  --table users \
  --stdin \
  --format csv
```

## CLI オプション

| オプション | 説明 | デフォルト |
|-----------|------|-----------|
| `--endpoint <URI>` | Flight endpoint (必須) | — |
| `--table <name>` | 対象テーブル名 (必須) | — |
| `--file <path>` | 入力ファイルパス | — |
| `--stdin` | 標準入力から読込み | false |
| `--format` | csv / tsv | csv |
| `--header` / `--no-header` | ヘッダー行の有無 | true |
| `--jdbc-url <URL>` | JDBC URL（メタデータ取得用） | endpointから推測 |
| `--validate-only` | 検証のみ（データ送信なし） | false |
| `--null-string <val>` | NULL マーカー | `\N` |
| `--verbose` | 詳細出力 | false |

## クライアント側検証

インポート前に全行の型検証を行う。エラーが1件でもあればデータを送信しない。

| 検証項目 | 内容 |
|---------|------|
| 列数 | テーブル定義と一致すること |
| INTEGER | 10進整数として有効 |
| BIGINT | 10進整数、範囲内 |
| DOUBLE | 浮動小数点として有効 |
| BOOLEAN | true/false/0/1 のいずれか |
| VARCHAR | 常に有効 |

検証エラー時の出力例:
```
Validation failed. No data was sent.

  line 124: column "age": "abc" is not INT
  line 201: expected 4 columns but found 5

Errors: 2
```

## ディレクトリ構成

```
cli/
└── src/
    ├── main/java/com/example/rdb/cli/
    │   ├── ErdbCli.java          ← エントリポイント (picocli)
    │   └── ImportCommand.java    ← importサブコマンド
    └── test/java/                 ← (今後追加)

src/main/java/com/example/rdb/remote/
├── ExampleRdbServer.java          ← 統合サーバーランチャー (Avatica + Flight)
├── ExampleFlightSqlServer.java    ← Flight SQLサーバー
├── ErdbFlightSqlProducer.java     ← FlightSqlProducer実装 (Bulk Ingest)
├── ExampleAvaticaServer.java      ← Avaticaサーバー (既存)
└── ExampleJdbcMeta.java           ← Avaticaメタデータ (既存)
```

## 技術構成

| コンポーネント | バージョン |
|--------------|-----------|
| Apache Arrow Java | 19.0.0 |
| Arrow Flight SQL | 19.0.0 |
| Protobuf Java | 4.33.4 |
| picocli | 4.7.6 |
| Apache Commons CSV | 1.11.0 |

## 既知の制限事項

| 制限 | 説明 |
|------|------|
| APPENDモードのみ | REPLACE、CREATE は未対応 |
| テーブル自動作成なし | 対象テーブルが存在しない場合はエラー |
| 全件メモリ読込み | 現在CSV全行をメモリに保持（ストリーミングは将来対応） |
| Flight SQLメタデータ未対応 | メタデータはJDBC経由で取得 |
| 排他制御未実装 | 複数クライアントからの同時インポートは未保護 |
| 認証・TLSなし | 信頼できるネットワーク内でのみ使用 |

## 今後の拡張候補

- Arrow IPCファイル入力対応
- ストリーミング転送（メモリ制限付きバッチ送信）
- テーブルロック管理（排他制御）
- Flight SQLメタデータAPI完全対応
- Generation/Manifest によるatomic publish
- 外部ソートによるCovering Index一括再構築
- ローカルArrow IPCステージング（送信前スプール）

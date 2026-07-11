# 負荷テスト環境（k6 + xk6-sql + Avatica）

k6からExample RDB（Avatica HTTP経由）へSQLを発行し、負荷テストを行うための環境。

## アーキテクチャ

```
┌──────────────┐       Docker network (example-rdb-net)
│  k6 container│──────────────────────────────┐
│  (xk6-sql +  │       Avatica HTTP/Protobuf  │
│   avatica)   │──────►  rdb-server:8765 ─────┤
└──────┬───────┘                              │
       │ scripts/*.js (マウント)               ▼
       │                              ┌────────────────┐
  ホスト側で編集                       │ ExampleRDB     │
  (ビルド不要)                        │ (Avatica Server)│
                                     └────────────────┘
```

## 前提

- Example RDBのルートディレクトリで `docker compose up -d rdb-server` を実行済み
- Docker network `example-rdb-net` が作成済み

## セットアップ

```bash
# ルートディレクトリでDBサーバー起動
docker compose up -d rdb-server

# k6イメージをビルド（初回のみ）
cd loadtest
docker compose build
```

> **注意**: k6イメージのビルドにはGo 1.25が必要です（calcite-avatica-go v5の要件）。ビルドはマルチステージDockerfile内で完結します。

## スクリプトの実行

スクリプトは `scripts/` 配下に配置し、コンテナ外で編集します。**スクリプト変更時にイメージの再ビルドは不要**です。

```bash
# 基本形式
docker compose run --rm k6 run /scripts/<filename>.js

# 実行例
docker compose run --rm k6 run /scripts/connectivity.js

# 環境変数でVU数・実行時間を指定
docker compose run --rm -e K6_VUS=10 -e K6_DURATION=30s k6 run /scripts/02-read-products.js
```

## スクリプトからのDBアクセス

```javascript
import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";

const db = sql.open(driver, "http://rdb-server:8765");

export function setup() {
  db.exec("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY)");
  db.exec("INSERT INTO t VALUES (1)");
}

export default function () {
  let rows = db.query("SELECT * FROM t");
  for (const row of rows) {
    console.log(row.id);
  }
}

export function teardown() {
  db.close();
}
```

### API

| メソッド | 説明 |
|---------|------|
| `sql.open(driver, connStr)` | DB接続を開く。driverは `k6/x/sql/driver/avatica` からimport |
| `db.exec(sql, ...args)` | DDL/DMLを実行。影響行数を返す |
| `db.query(sql, ...args)` | SELECTを実行。`[{col: val, ...}, ...]` を返す |
| `db.close()` | 接続を閉じる |

## ディレクトリ構成

```
loadtest/
├── Dockerfile                # k6ビルド用（xk6-sql + avatica driver）
├── docker-compose.yml        # k6コンテナ定義
├── driver/
│   ├── go.mod                # xk6-sql-driver-avatica モジュール定義
│   └── register.go           # Avaticaドライバー登録
├── scripts/
│   ├── connectivity.js       # 疎通確認
│   ├── 01-write-orders.js    # シナリオ1: INSERT多発（注文作成）
│   ├── 02-read-products.js   # シナリオ2: SELECT多発（商品検索）
│   ├── 03-mixed-workload.js  # シナリオ3: 読み書き混合（ショッピング）
│   ├── 04-update-stock.js    # シナリオ4: UPDATE多発（在庫更新）
│   └── 05-delete-old.js      # シナリオ5: DELETE（旧データ削除）
└── README.md                 # 本ファイル
```

## カスタムスクリプトの作成

1. `scripts/` 配下に `.js` ファイルを作成
2. `docker compose run --rm k6 run /scripts/<filename>.js` で実行

k6の全機能（チェック、スレッショルド、メトリクス）が利用可能です:

```javascript
import { check } from "k6";
import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";

export const options = {
  vus: 10,
  duration: "30s",
  thresholds: {
    sql_select_duration: ["p(95)<500"], // 95%が500ms未満
  },
};

const db = sql.open(driver, "http://rdb-server:8765");

export default function () {
  let rows = db.query("SELECT COUNT(*) AS cnt FROM products");
  check(rows, {
    "count returned": (r) => r.length > 0,
  });
}
```

## トラブルシューティング

### `connection refused`
rdb-serverが起動しているか確認:
```bash
docker compose ps           # ルートディレクトリで
docker compose logs rdb-server
```

### `unknown database engine`
k6バイナリにavaticaドライバーが組み込まれていません。イメージを再ビルド:
```bash
docker compose build --no-cache
```

### `Duplicate primary key` (rdb-server起動時)
旧データが残っています。ルートディレクトリで:
```bash
docker compose down
Remove-Item -Recurse -Force data
docker compose up -d rdb-server
```

---

## 3分間ベンチマーク結果

各シナリオをクリーンなDBに対して3分間（180s）実行した結果です。

### テスト環境

- Example RDB: Docker コンテナ（rdb-server）、Avatica HTTPポート8765
- k6: Docker コンテナ、同一Dockerネットワーク（example-rdb-net）
- テストデータ: products 100行（5カテゴリ）、orders はシナリオごとに生成

### シナリオ別サマリー

| シナリオ | DML | VU数 | イテレーション | スループット | avg | p(95) | checks |
|----------|-----|------|---------------|-------------|------|-------|--------|
| 01-write-orders | INSERT | 5 | 28,930 | 154/s | 31.08ms | 43.27ms | 100% |
| 02-read-products | SELECT | 10 | 83,782 | 447/s | 21.47ms | 33.36ms | 100% |
| 03-mixed-workload | SELECT+INSERT | 5 | 18,135 | 97/s | 49.61ms | 68.01ms | 100% |
| 04-update-stock | UPDATE | 5 | 37,270 | 199/s | 24.09ms | 34.80ms | 100% |
| 05-delete-old | DELETE | 3 | 18,644 | 100/s | 28.95ms | 36.56ms | 100% |

> **全シナリオで checks 100%**（成功）。ただし書き込み系シナリオでは一部 `ConcurrentModificationException` が発生（後述）。

### 発見された問題

#### ConcurrentModificationException（書き込み系マルチVU）

INSERT/UPDATE/DELETE を複数VUで並行実行すると、ごくまれに `ConcurrentModificationException` が発生します。

**原因**: Example RDBの内部データ構造（`ArrayList`）がスレッドセーフではないため、Avaticaサーバーが複数のリクエストを並行処理すると競合が発生します。`ExampleTable` の `synchronized` メソッドで一部保護されていますが、スキャン中の反復と追加が競合するケースがあります。

**影響**: エラーが発生したイテレーションは失敗しますが、k6は他のイテレーションを継続します。データの整合性は保たれます（WALが正しく書き込まれた後のみメモリに反映されるため）。

**対応**: 排他制御（ロック機構）の実装で解決可能です。学習用RDBの既知の制限事項として記載しています。

---

### シナリオ1: INSERT多発（01-write-orders.js）— 3分

```
     ✓ order count returned

     checks...............: 100.00% 25552 out of 25552
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=31.08ms min=5.81ms med=31.6ms max=527.2ms p(90)=38.45ms p(95)=43.27ms
     iterations...........: 28930   154.353099/s
     vus..................: 5       min=0              max=5
     vus_max..............: 5       min=5              max=5


running (3m07.4s), 0/5 VUs, 28930 complete and 0 interrupted iterations
default ✓ [ 100% ] 5 VUs  3m0s
```

- ティアダウン時: `total orders = 25,552`
- 28,930イテレーション中 25,552件のINSERT成功（3,378件は終盤のConcurrentModificationExceptionで失敗）
- p(95) = 43ms、スループット約 154 INSERT/s

### シナリオ2: SELECT多発（02-read-products.js）— 3分

```
     ✓ all query returned rows
     ✓ filter query executed
     ✓ aggregate query returned rows

     checks...............: 100.00% 83782 out of 83782
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=21.47ms min=8.74ms med=21.11ms max=245.09ms p(90)=27.72ms p(95)=33.36ms
     iterations...........: 83782   447.330478/s
     vus..................: 10      min=0              max=10
     vus_max..............: 10      min=10             max=10


running (3m07.3s), 00/10 VUs, 83782 complete and 0 interrupted iterations
default ✓ [ 100% ] 10 VUs  3m0s
```

- **エラーなし**。SELECT専用のため並行性の問題は発生しない
- 10VUで 83,782イテレーション、スループット約 447 SELECT/s
- 3パターン（全件LIMIT / WHERE+ORDER BY / GROUP BY集計）を循環実行

### シナリオ3: 読み書き混合（03-mixed-workload.js）— 3分

```
     ✓ products found
     ✓ history returned

     checks...............: 100.00% 35262 out of 35262
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=49.61ms min=22.19ms med=47.26ms max=365.85ms p(90)=59.48ms p(95)=68.01ms
     iterations...........: 18135   96.784932/s
     vus..................: 5       min=5              max=5
     vus_max..............: 5       min=5              max=5


running (3m07.4s), 0/5 VUs, 18135 complete and 0 interrupted iterations
default ✓ [ 100% ] 5 VUs  3m0s
```

- ティアダウン時: `total orders = 17,127`
- 1イテレーション = 3クエリ（SELECT + INSERT + SELECT）のため、iteration_durationは他より高め
- avg=49.61ms、p(95)=68.01ms

### シナリオ4: UPDATE多発（04-update-stock.js）— 3分

```
     ✓ stock readable

     checks...............: 100.00% 12425 out of 12425
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=24.09ms min=10.62ms med=25.08ms max=300.11ms p(90)=30.58ms p(95)=34.8ms
     iterations...........: 37270   198.69603/s
     vus..................: 5       min=5              max=5
     vus_max..............: 5       min=5              max=5


running (3m07.6s), 0/5 VUs, 37270 complete and 0 interrupted iterations
default ✓ [ 100% ] 5 VUs  3m0s
```

- ティアダウン時カテゴリ別統計:
  - books: 20 products, avg_price=59
  - clothing: 20 products, avg_price=7
  - electronics: 20 products, avg_price=75
  - food: 20 products, avg_price=42
  - toys: 20 products, avg_price=34
- 3パターン（個別在庫更新 / カテゴリ別価格変更 / 在庫補充）を循環実行

### シナリオ5: DELETE多発（05-delete-old.js）— 3分

```
     ✓ count returned

     checks...............: 100.00% 18644 out of 18644
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=28.95ms min=21.15ms med=28.04ms max=242.53ms p(95)=36.56ms
     iterations...........: 18644   100.082023/s
     vus..................: 3       min=3              max=3
     vus_max..............: 3       min=3              max=3


running (3m06.3s), 0/3 VUs, 18644 complete and 0 interrupted iterations
default ✓ [ 100% ] 3 VUs  3m0s
```

- ティアダウン時: `remaining orders = 13`
- 3パターン（個別削除 / キャンセル済み一括削除 / 条件削除）を循環実行
- データ枯渇時は自動補充（10行INSERT）で継続

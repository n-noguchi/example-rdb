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
│   ├── 05-delete-old.js      # シナリオ5: DELETE（旧データ削除）
│   └── 06-covering-index.js  # シナリオ6: Covering Index Scan（等価検索）
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

### シナリオ別サマリー（第2回 — ConcurrentModificationException修正後 + Covering Index追加）

| シナリオ | DML | VU数 | イテレーション | スループット | avg | p(95) | checks |
|----------|-----|------|---------------|-------------|------|-------|--------|
| 01-write-orders | INSERT | 5 | 27,089 | 144/s | 33.21ms | 42.58ms | 100% |
| 02-read-products | SELECT | 10 | 83,429 | 446/s | 21.56ms | 33.20ms | 100% |
| 03-mixed-workload | SELECT+INSERT | 5 | 17,004 | 91/s | 52.93ms | 71.26ms | 100% |
| 04-update-stock | UPDATE | 5 | 37,805 | 202/s | 23.75ms | 33.69ms | 100% |
| 05-delete-old | DELETE | 3 | 19,148 | 103/s | 28.19ms | 35.91ms | 100% |
| **06-covering-index** | **SELECT (Index)** | **10** | **120,365** | **639/s** | **14.94ms** | **23.20ms** | **100%** |

> 全シナリオで checks 100%、エラーなし。ConcurrentModificationExceptionはゼロ件。

### Covering Index vs Full Scan 比較

| 項目 | 02-read-products (Full Scan) | 06-covering-index (Index Scan) | 差 |
|------|------|------|-----|
| VU数 | 10 | 10 | 同条件 |
| データ件数 | 100行 | 100行 | 同条件 |
| クエリ | SELECT+WHERE+GROUP BY 3パターン | SELECT WHERE col=val (Index Only) | — |
| スループット | 446/s | 639/s | **1.43倍** |
| avgレイテンシ | 21.56ms | 14.94ms | **31%短縮** |
| p(95)レイテンシ | 33.20ms | 23.20ms | **30%短縮** |

---

### シナリオ1: INSERT多発（01-write-orders.js）— 3分

```
     ✓ order count returned

     checks...............: 100.00% 27089 out of 27089
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=33.21ms min=22.92ms med=31.87ms max=519.38ms p(90)=37.56ms p(95)=42.58ms
     iterations...........: 27089   144.529437/s
     vus..................: 5       min=0              max=5
     vus_max..............: 5       min=5              max=5


running (3m07.4s), 0/5 VUs, 27089 complete and 0 interrupted iterations
default ✓ [ 100% ] 5 VUs  3m0s
```

- ティアダウン時: `total orders = 27,089`
- エラーなし（CopyOnWriteArrayList修正後）

### シナリオ2: SELECT多発（02-read-products.js）— 3分

```
     ✓ all query returned rows
     ✓ filter query executed
     ✓ aggregate query returned rows

     checks...............: 100.00% 83429 out of 83429
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=21.56ms min=8.15ms med=21.21ms max=283.5ms p(90)=27.71ms p(95)=33.2ms
     iterations...........: 83429   445.572756/s
     vus..................: 10      min=0              max=10
     vus_max..............: 10      min=10             max=10


running (3m07.2s), 00/10 VUs, 83429 complete and 0 interrupted iterations
default ✓ [ 100% ] 10 VUs  3m0s
```

- エラーなし。10VUで 83,429イテレーション、スループット約 446 SELECT/s

### シナリオ3: 読み書き混合（03-mixed-workload.js）— 3分

```
     ✓ products found
     ✓ history returned

     checks...............: 100.00% 34008 out of 34008
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=52.93ms min=39.54ms med=49.95ms max=372.9ms p(90)=61.71ms p(95)=71.26ms
     iterations...........: 17004   90.745702/s
     vus..................: 5       min=0              max=5
     vus_max..............: 5       min=5              max=5


running (3m07.4s), 0/5 VUs, 17004 complete and 0 interrupted iterations
default ✓ [ 100% ] 5 VUs  3m0s
```

- エラーなし（CopyOnWriteArrayList修正後）

### シナリオ4: UPDATE多発（04-update-stock.js）— 3分

```
     ✓ stock readable

     checks...............: 100.00% 12604 out of 12604
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=23.75ms min=10.91ms med=24.86ms max=285.52ms p(90)=29.83ms p(95)=33.69ms
     iterations...........: 37805   201.691537/s
     vus..................: 5       min=0              max=5
     vus_max..............: 5       min=5              max=5


running (3m07.4s), 0/5 VUs, 37805 complete and 0 interrupted iterations
default ✓ [ 100% ] 5 VUs  3m0s
```

- ティアダウン時カテゴリ別統計:
  - books: 20 products, avg_price=37
  - clothing: 20 products, avg_price=59
  - electronics: 20 products, avg_price=83
  - food: 20 products, avg_price=48
  - toys: 20 products, avg_price=14
- 3パターン（個別在庫更新 / カテゴリ別価格変更 / 在庫補充）を循環実行

### シナリオ5: DELETE多発（05-delete-old.js）— 3分

```
     ✓ count returned

     checks...............: 100.00% 19148 out of 19148
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=28.19ms min=20.37ms med=27.3ms max=236.09ms p(90)=32.45ms p(95)=35.91ms
     iterations...........: 19148   102.775892/s
     vus..................: 3       min=3              max=3
     vus_max..............: 3       min=3              max=3


running (3m06.3s), 0/3 VUs, 19148 complete and 0 interrupted iterations
default ✓ [ 100% ] 3 VUs  3m0s
```

- ティアダウン時: `remaining orders = 13`
- 3パターン（個別削除 / キャンセル済み一括削除 / 条件削除）を循環実行

### シナリオ6: Covering Index Scan（06-covering-index.js）— 3分

```
     ✓ index scan returned rows

     checks...............: 100.00% 120365 out of 120365
     data_received........: 0 B     0 B/s
     data_sent............: 0 B     0 B/s
   ✓ iteration_duration...: avg=14.94ms min=7.4ms med=13.55ms max=205.05ms p(90)=18.89ms p(95)=23.2ms
     iterations...........: 120365  638.732972/s
     vus..................: 10      min=0                max=10
     vus_max..............: 10      min=10               max=10


running (3m08.4s), 00/10 VUs, 120365 complete and 0 interrupted iterations
default ✓ [ 100% ] 10 VUs  3m0s
```

- テストデータ: products 100行、Covering Index `idx_products_cat(category) INCLUDE (name, price, stock)`
- クエリ: `SELECT name, price, stock FROM products WHERE category = 'electronics'`（等価検索、Index Only Scan）
- Full Scan（シナリオ02）と比較してスループット **1.43倍**、p(95)レイテンシ **30%短縮**

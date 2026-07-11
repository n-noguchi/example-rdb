import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";
import { check } from "k6";

const db = sql.open(driver, "http://rdb-server:8765");

export const options = {
  vus: __ENV.K6_VUS ? parseInt(__ENV.K6_VUS) : 3,
  duration: __ENV.K6_DURATION || "10s",
  thresholds: {
    iteration_duration: ["p(95)<2000"],
  },
};

export function setup() {
  db.exec("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY, user_id INTEGER, product_id INTEGER, quantity INTEGER, status VARCHAR, total DOUBLE)");

  let count = db.query("SELECT COUNT(*) AS cnt FROM orders");
  if (parseInt(count[0].cnt) < 50) {
    for (let i = 1; i <= 50; i++) {
      let status = i % 3 === 0 ? "cancelled" : "confirmed";
      db.exec(`INSERT INTO orders VALUES (${i}, ${i % 10}, ${(i % 100) + 1}, ${(i % 5) + 1}, '${status}', ${(i * 2.5).toFixed(2)})`);
    }
    console.log("setup: orders seeded (50 rows)");
  }
}

export default function () {
  const orderId = (__ITER % 50) + 1;

  // パターン1: 特定注文の削除
  if (__ITER % 3 === 0) {
    db.exec(`DELETE FROM orders WHERE id = ${orderId}`);
  }
  // パターン2: キャンセル済み注文の一括削除
  else if (__ITER % 3 === 1) {
    db.exec("DELETE FROM orders WHERE status = 'cancelled'");
  }
  // パターン3: 条件なし削除（少量）
  else {
    db.exec("DELETE FROM orders WHERE quantity > 10");
  }

  let count = db.query("SELECT COUNT(*) AS cnt FROM orders");
  check(count, { "count returned": (r) => r.length > 0 });

  // データが枯渇したら補充
  if (parseInt(count[0].cnt) < 5) {
    let startId = Date.now() % 100000;
    for (let i = 0; i < 10; i++) {
      db.exec(`INSERT INTO orders VALUES (${startId + i}, ${__VU}, ${(i % 100) + 1}, ${(i % 5) + 1}, 'confirmed', ${(i * 2.5).toFixed(2)})`);
    }
  }
}

export function teardown() {
  let count = db.query("SELECT COUNT(*) AS cnt FROM orders");
  console.log(`teardown: remaining orders = ${count[0].cnt}`);
  db.close();
}

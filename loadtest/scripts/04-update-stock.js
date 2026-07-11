import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";
import { check } from "k6";

const db = sql.open(driver, "http://rdb-server:8765");

export const options = {
  vus: __ENV.K6_VUS ? parseInt(__ENV.K6_VUS) : 5,
  duration: __ENV.K6_DURATION || "15s",
  thresholds: {
    iteration_duration: ["p(95)<2000"],
  },
};

export function setup() {
  db.exec("CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY, name VARCHAR, category VARCHAR, price DOUBLE, stock INTEGER)");

  let pcount = db.query("SELECT COUNT(*) AS cnt FROM products");
  if (parseInt(pcount[0].cnt) === 0) {
    const categories = ["electronics", "books", "clothing", "food", "toys"];
    for (let i = 1; i <= 100; i++) {
      db.exec(`INSERT INTO products VALUES (${i}, 'Product-${i}', '${categories[i % categories.length]}', ${(i * 1.5).toFixed(2)}, 50)`);
    }
    console.log("setup: products seeded (stock=50 for all)");
  } else {
    db.exec("UPDATE products SET stock = 50");
    console.log("setup: stock reset to 50 for all products");
  }
}

export default function () {
  const productId = (__ITER % 100) + 1;

  // パターン1: 特定商品の在庫を減らす
  if (__ITER % 3 === 0) {
    db.exec(`UPDATE products SET stock = stock WHERE id = ${productId}`);
    let rows = db.query(`SELECT stock FROM products WHERE id = ${productId}`);
    check(rows, { "stock readable": (r) => r.length > 0 });
  }
  // パターン2: カテゴリ全体の価格変更
  else if (__ITER % 3 === 1) {
    let cat = ["electronics", "books", "clothing", "food", "toys"][__ITER % 5];
    let newPrice = ((Date.now() % 100) + 1).toFixed(2);
    db.exec(`UPDATE products SET price = ${newPrice} WHERE category = '${cat}'`);
  }
  // パターン3: 全商品の在庫リセット
  else {
    db.exec("UPDATE products SET stock = 50 WHERE stock < 10");
  }
}

export function teardown() {
  let stats = db.query("SELECT category, COUNT(*) AS cnt, AVG(price) AS avg_price FROM products GROUP BY category ORDER BY category");
  for (const row of stats) {
    console.log(`${row.category}: ${row.cnt} products, avg_price=${row.avg_price}`);
  }
  db.close();
}

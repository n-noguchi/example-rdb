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
  db.exec("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY, user_id INTEGER, product_id INTEGER, quantity INTEGER, status VARCHAR, total DOUBLE)");

  let pcount = db.query("SELECT COUNT(*) AS cnt FROM products");
  if (parseInt(pcount[0].cnt) === 0) {
    const categories = ["electronics", "books", "clothing", "food", "toys"];
    for (let i = 1; i <= 100; i++) {
      db.exec(`INSERT INTO products VALUES (${i}, 'Product-${i}', '${categories[i % categories.length]}', ${(i * 1.5).toFixed(2)}, ${(100 - i)})`);
    }
  }

  db.exec("DELETE FROM orders");
  console.log("setup: ready (100 products, orders cleared)");
}

export default function () {
  // 1. Browse: 商品を検索
  let cat = ["electronics", "books", "clothing", "food", "toys"][__ITER % 5];
  let products = db.query(`SELECT id, name, price FROM products WHERE category = '${cat}' ORDER BY price LIMIT 5`);
  check(products, { "products found": (r) => r.length > 0 });

  if (products.length === 0) return;

  // 2. Checkout: 注文を作成
  let chosen = products[__ITER % products.length];
  let qty = (__ITER % 3) + 1;
  let orderId = __VU * 1000000 + __ITER + 1;
  let total = (qty * parseFloat(chosen.price)).toFixed(2);

  db.exec(`INSERT INTO orders VALUES (${orderId}, ${__VU}, ${chosen.id}, ${qty}, 'confirmed', ${total})`);

  // 3. History: 自分の注文履歴を確認
  let history = db.query(`SELECT id, product_id, quantity, status FROM orders WHERE user_id = ${__VU} ORDER BY id DESC LIMIT 5`);
  check(history, { "history returned": (r) => r !== null });
}

export function teardown() {
  let ocount = db.query("SELECT COUNT(*) AS cnt FROM orders");
  console.log(`teardown: total orders = ${ocount[0].cnt}`);
  db.close();
}

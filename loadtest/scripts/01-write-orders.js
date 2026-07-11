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

const VU_ID_PREFIX = `vu-${__VU}`;

export function setup() {
  db.exec("CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY, name VARCHAR, category VARCHAR, price DOUBLE, stock INTEGER)");
  db.exec("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY, user_id INTEGER, product_id INTEGER, quantity INTEGER, status VARCHAR, total DOUBLE)");

  db.exec("DELETE FROM orders");
  db.exec("DELETE FROM products");

  const categories = ["electronics", "books", "clothing", "food", "toys"];
  for (let i = 1; i <= 100; i++) {
    db.exec(`INSERT INTO products VALUES (${i}, 'Product-${i}', '${categories[i % categories.length]}', ${(i * 1.5).toFixed(2)}, ${(100 - i)})`);
  }

  console.log("setup: products seeded (100 rows)");
}

export default function () {
  const productId = (__ITER % 100) + 1;
  const quantity = (__ITER % 5) + 1;
  const orderId = __VU * 1000000 + __ITER + 1;
  const unitPrice = (productId * 1.5).toFixed(2);
  const total = (quantity * parseFloat(unitPrice)).toFixed(2);

  db.exec(
    `INSERT INTO orders VALUES (${orderId}, ${__VU}, ${productId}, ${quantity}, 'pending', ${total})`
  );

  let rows = db.query(`SELECT COUNT(*) AS cnt FROM orders WHERE user_id = ${__VU}`);
  check(rows, {
    "order count returned": (r) => r.length > 0,
  });
}

export function teardown() {
  let count = db.query("SELECT COUNT(*) AS cnt FROM orders");
  console.log(`teardown: total orders = ${count[0].cnt}`);
  db.close();
}

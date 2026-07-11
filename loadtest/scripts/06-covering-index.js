import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";
import { check } from "k6";

const db = sql.open(driver, "http://rdb-server:8765");

export const options = {
  vus: __ENV.K6_VUS ? parseInt(__ENV.K6_VUS) : 10,
  duration: __ENV.K6_DURATION || "15s",
  thresholds: {
    iteration_duration: ["p(95)<500"],
  },
};

export function setup() {
  db.exec("CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY, name VARCHAR, category VARCHAR, price DOUBLE, stock INTEGER)");
  db.exec("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY, user_id INTEGER, product_id INTEGER, quantity INTEGER, status VARCHAR, total DOUBLE)");

  db.exec("DELETE FROM orders");
  db.exec("DELETE FROM products");

  const categories = ["electronics", "books", "clothing", "food", "toys"];
  for (let i = 1; i <= 100; i++) {
    db.exec(`INSERT INTO products VALUES (${i}, 'Product-${i}', '${categories[i % categories.length]}', ${(i * 1.5).toFixed(2)}, ${(100 - i)})`);
  }

  // Create covering index for point lookups
  db.exec("CREATE INDEX idx_products_cat ON products(category) INCLUDE (name, price, stock)");

  console.log("setup: 100 products + covering index idx_products_cat");
}

export default function () {
  // Point lookup via covering index: category = X
  const cat = ["electronics", "books", "clothing", "food", "toys"][__ITER % 5];
  let rows = db.query(`SELECT name, price, stock FROM products WHERE category = '${cat}'`);
  check(rows, { "index scan returned rows": (r) => r.length > 0 });
}

export function teardown() {
  db.close();
}

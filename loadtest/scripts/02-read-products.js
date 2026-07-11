import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";
import { check } from "k6";

const db = sql.open(driver, "http://rdb-server:8765");

export const options = {
  vus: __ENV.K6_VUS ? parseInt(__ENV.K6_VUS) : 10,
  duration: __ENV.K6_DURATION || "15s",
  thresholds: {
    iteration_duration: ["p(95)<1000"],
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
    console.log("setup: products seeded (100 rows)");
  }
}

export default function () {
  const pattern = __ITER % 3;

  if (pattern === 0) {
    let rows = db.query("SELECT id, name, price FROM products ORDER BY id LIMIT 10");
    check(rows, { "all query returned rows": (r) => r.length > 0 });
  } else if (pattern === 1) {
    let cat = ["electronics", "books", "clothing", "food", "toys"][__ITER % 5];
    let rows = db.query(`SELECT id, name FROM products WHERE category = '${cat}' ORDER BY price DESC LIMIT 5`);
    check(rows, { "filter query executed": (r) => r !== null });
  } else {
    let rows = db.query("SELECT category, COUNT(*) AS cnt, AVG(price) AS avg_price FROM products GROUP BY category ORDER BY category");
    check(rows, { "aggregate query returned rows": (r) => r.length > 0 });
  }
}

export function teardown() {
  db.close();
}

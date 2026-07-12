import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";
import { check } from "k6";

const db = sql.open(driver, "http://rdb-server:8765");

export const options = {
  vus: __ENV.K6_VUS ? parseInt(__ENV.K6_VUS) : 5,
  iterations: __ENV.K6_ITERATIONS ? parseInt(__ENV.K6_ITERATIONS) : undefined,
  duration: __ENV.K6_DURATION || undefined,
  thresholds: {
    iteration_duration: ["p(95)<30000"],
  },
};

const BATCH_SIZE = __ENV.BATCH_SIZE ? parseInt(__ENV.BATCH_SIZE) : 50;

export function setup() {
  db.exec("CREATE TABLE IF NOT EXISTS bulk_import_test (id INTEGER PRIMARY KEY, name VARCHAR, category VARCHAR, price DOUBLE)");
  db.exec("DELETE FROM bulk_import_test");

  let count = db.query("SELECT COUNT(*) AS cnt FROM bulk_import_test");
  console.log(`setup: batch_size=${BATCH_SIZE}, existing_rows=${count[0].cnt}`);
}

let batchCounter = 0;

export default function () {
  const vuPrefix = __VU * 10000000;

  const values = [];
  for (let i = 0; i < BATCH_SIZE; i++) {
    const id = vuPrefix + batchCounter * BATCH_SIZE + i + 1;
    const cat = ["electronics", "books", "clothing", "food", "toys"][i % 5];
    values.push(`(${id}, 'Product-${id}', '${cat}', ${(id * 1.5).toFixed(2)})`);
  }
  batchCounter++;

  db.exec(`INSERT INTO bulk_import_test VALUES ${values.join(", ")}`);

  let rows = db.query("SELECT COUNT(*) AS cnt FROM bulk_import_test");
  check(rows, {
    "count returned": (r) => r.length > 0,
  });
}

export function handleSummary(data) {
  const iter = data.metrics.iterations?.values?.count || 0;
  const totalRows = iter * BATCH_SIZE;
  console.log(`summary: ${iter} batches × ${BATCH_SIZE} rows = ${totalRows} total rows imported`);
  return {};
}

export function teardown() {
  let count = db.query("SELECT COUNT(*) AS cnt FROM bulk_import_test");
  console.log(`teardown: total rows = ${count[0].cnt}`);
  db.close();
}

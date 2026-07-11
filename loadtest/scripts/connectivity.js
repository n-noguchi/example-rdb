import sql from "k6/x/sql";
import driver from "k6/x/sql/driver/avatica";

const db = sql.open(driver, "http://rdb-server:8765");

export function setup() {
  db.exec("CREATE TABLE IF NOT EXISTS loadtest_users (id INTEGER PRIMARY KEY, name VARCHAR)");
  db.exec("DELETE FROM loadtest_users");
  db.exec("INSERT INTO loadtest_users VALUES (1, 'Alice')");
  db.exec("INSERT INTO loadtest_users VALUES (2, 'Bob')");
  db.exec("INSERT INTO loadtest_users VALUES (3, 'Charlie')");
}

export function teardown() {
  db.close();
}

export default function () {
  let rows = db.query("SELECT id, name FROM loadtest_users ORDER BY id");
  for (const row of rows) {
    console.log(`id=${row.id}, name=${row.name}`);
  }

  let count = db.query("SELECT COUNT(*) AS cnt FROM loadtest_users");
  console.log(`count=${count[0].cnt}`);
}

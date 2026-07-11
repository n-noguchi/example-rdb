// Package avatica contains Avatica driver registration for xk6-sql.
package avatica

import (
	"github.com/grafana/xk6-sql/sql"

	// Blank import required for initialization of Avatica driver in database/sql.
	_ "github.com/apache/calcite-avatica-go/v5"
)

func init() {
	sql.RegisterModule("avatica")
}

# ────────────────────────────────────────────
# Stage 1: Build k6 with xk6-sql + avatica
# ────────────────────────────────────────────
FROM golang:1.25-bookworm AS builder

ARG K6_VERSION=v0.56.0

# xk6をインストール
RUN go install go.k6.io/xk6/cmd/xk6@latest

WORKDIR /build

# ドライバー拡張をコピー
COPY driver/ /build/driver/

# xk6-sql v1.0.0 は k6 v0.x 系と互換（v1.2.0 は k6 v2 が必要）
RUN xk6 build \
    --with github.com/grafana/xk6-sql@v1.0.0 \
    --with github.com/example/xk6-sql-driver-avatica=/build/driver \
    --output /k6

# ────────────────────────────────────────────
# Stage 2: Runtime
# ────────────────────────────────────────────
FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /k6 /usr/local/bin/k6

ENTRYPOINT ["k6"]
CMD ["run"]

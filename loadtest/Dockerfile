FROM golang:1.25.2-alpine AS builder

RUN apk add --no-cache git
RUN go install go.k6.io/xk6/cmd/xk6@v0.13.0
RUN CGO_ENABLED=0 xk6 build v0.54.0 \
    --with github.com/grafana/xk6-sql@v1.0.0 \
    --with github.com/grafana/xk6-sql-driver-mysql@v0.1.0 \
    --output /tmp/k6

FROM grafana/k6:0.54.0
COPY --from=builder /tmp/k6 /usr/bin/k6

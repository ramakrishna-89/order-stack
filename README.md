# Order Processing Platform

A microservices-based order processing platform built to handle **5,000+ orders per minute** with real-time status updates, full observability, and enterprise-grade resilience.

---

## The Brief

Design a platform capable of:

- Accepting and persisting orders at high throughput (5,000+ orders/min)
- Publishing order events to a message broker
- Pushing real-time status updates to a live dashboard
- Meeting enterprise non-functional requirements: scalability, resiliency, observability

Two services were required — **Order Service** (REST API + event publishing) and **Notification Service** (event consumer + real-time push). A live Angular dashboard was added to make the real-time flow visible end-to-end.

---

## Quick Start

**Prerequisites:** Docker + Docker Compose + (Install [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) to run the bundled smoke and load tests.)

```bash
# Infra

# Git
git clone <repo-url>
cd order-stack

cp infra/.env.example infra/.env

docker compose -f infra/compose.yml up -d

# Wait ~30 seconds for all health checks to pass and ensure the infra is up

# Smoke test — 3 iterations via Traefik (auth, order lifecycle, all UI routes)
cd test && k6 run smoke-test-traefik.js

# Full load test — ~5,000 orders/min sustained + 250 req/sec burst (direct, bypasses rate limit)
cd test && k6 run order-load-test.js

```

Wait ~30 seconds for all health checks to pass, then open:

| Service | Traefik URL `:8888` | Direct URL | Credentials |
|---|---|---|---|
| **Dashboard** | [http://localhost:8888](http://localhost:8888) | [http://localhost:4200](http://localhost:4200) | Traefik: admin / admin@12 |
| **Order Service API** | [http://localhost:8888/orders](http://localhost:8888/orders) | [http://localhost:8080/orders](http://localhost:8080/orders) | rate limited via Traefik |
| **Grafana** | [http://localhost:8888/grafana](http://localhost:8888/grafana) | [http://localhost:3010](http://localhost:3010) | Grafana: admin / admin123 |
| **Kafka UI** | [http://localhost:8888/kafka-ui](http://localhost:8888/kafka-ui) | [http://localhost:8090](http://localhost:8090) | Traefik: admin / admin@12 |
| **pgAdmin** | [http://localhost:8888/pgadmin](http://localhost:8888/pgadmin) | [http://localhost:5051](http://localhost:5051) | Traefik: admin / admin@12 · pgAdmin: admin@ecom.dev / admin123 |
| **Prometheus** | [http://localhost:8888/prometheus](http://localhost:8888/prometheus) | [http://localhost:9090/prometheus](http://localhost:9090/prometheus) | Traefik: admin / admin@12 |
| **Redis Insight** | [http://localhost:8888/redis](http://localhost:8888/redis) | [http://localhost:5541](http://localhost:5541) | Traefik: admin / admin@12 |
| **Traefik Dashboard** | — | [http://localhost:8889](http://localhost:8889) | — |

---

## Architecture

```
         Browser                          k6 load test
            │                                 │
            │  :8888 (protected)              │  :8080 (direct — bypasses Traefik)
            ▼                                 │
  ┌─────────────────────────┐                 │
  │      Traefik  :8888     │                 │
  │  ┌───────────────────┐  │                 │
  │  │ BasicAuth         │  │                 │
  │  │ Rate limit 100/s  │  │                 │
  │  │ File-based routing│  │                 │
  │  └───────────────────┘  │                 │
  └────────────┬────────────┘                 │
               │                              │
               └──────────────┬───────────────┘
                              │ POST /orders
                              │ GET  /orders/{id}
                              ▼
                  ┌─────────────────────────-┐
                  │      Order Service       │
                  │   Spring Boot · 8080     │
                  │  [REST API]  [Outbox     │
                  │              Poller]     │
                  └──────┬──────────┬────────┘
                         │          │ polls unpublished events
                         ▼          ▼
             ┌──────────────┐  ┌───────────────────┐
             │  PostgreSQL  │  │      Kafka        │
             │  orders      │  │  order-events     │
             │  outbox      │  │  (6 partitions)   │
             └──────────────┘  └────────┬──────────┘
                                        │ consume
             ┌──────────────┐           ▼
             │    Redis     │  ┌──────────────────┐
             │  idem cache  │  │ Notification Svc │
             │  order cache │  │ Spring Boot·8081 │
             └──────────────┘  └────────┬─────────┘
                                        │ SSE
                                        ▼
                               ┌──────────────────┐
                               │    Dashboard     │
                               │  Angular · 4200  │
                               └──────────────────┘

  Observability : Prometheus · Loki · Tempo · Grafana (:3010)
  All UIs via Traefik: /grafana · /kafka-ui · /pgadmin · /prometheus · /redis
```

---

## Demo

<table>
  <tr>
    <th>Live order stream (SSE → Dashboard)</th>
    <th>Grafana — metrics &amp; Tempo trace</th>
    <th>Loki — log analysis</th>
  </tr>
  <tr>
    <td><video src="https://github.com/user-attachments/assets/22b90094-cb40-45f7-88b9-074fd064ac9e" controls width="100%"></video></td>
    <td><video src="https://github.com/user-attachments/assets/f93c5333-c80b-4ffc-b6e8-09e95b4c8c07" controls width="100%"></video></td>
    <td><video src="https://github.com/user-attachments/assets/025ce11a-3892-434e-8f95-3b1dceddfc4e" controls width="100%"></video></td>
  </tr>
</table>

---

## Design Decisions

### Kafka — message broker
Kafka was chosen over RabbitMQ or SQS for its durability guarantees and partition-based parallelism. The `order-events` topic has **6 partitions** — each partition maps to one consumer thread, giving a theoretical ceiling of ~3,000 msg/sec per consumer instance. Scaling to 6 notification-service pods saturates all partitions with zero reconfiguration.

### Transactional Outbox Pattern
The order service writes the order and the outbox event in a **single database transaction**. A background poller reads unpublished events and sends them to Kafka. This eliminates the dual-write problem — if Kafka is down at the moment of the order, the event is not lost; it will be published when Kafka recovers. The DB is always the source of truth.

### Idempotency — three-layer defence
Every POST /orders requires an `X-Idempotency-Key` header. The service checks for duplicates in this order:
1. **Redis cache** — sub-millisecond rejection for the common case (key seen recently)
2. **SELECT before INSERT** — DB lookup after a Redis miss, handles keys that have expired from cache
3. **DB unique constraint** — last-resort guard for genuine concurrent races between pods

Using SELECT before INSERT avoids poisoning the active transaction with a constraint violation, which would abort all subsequent queries in the same TX.

### SSE over WebSocket
Notification service uses **Server-Sent Events** instead of WebSockets. Order status updates are unidirectional (server → client only), so WebSocket's bidirectional complexity adds no value. SSE works over plain HTTP/1.1, reconnects automatically on disconnect, and is simpler to proxy through nginx.

Each notification-service pod uses a **random UUID consumer group ID** so every pod receives the full event stream — correct for SSE fan-out where all connected clients need all events.

### Redis — volatile-lru eviction
Redis is configured with `maxmemory-policy volatile-lru`. Only keys with a TTL (idempotency keys at 24h, order cache at 30s) are eligible for eviction. The SSE pub/sub channel has no TTL and is never evicted. `noeviction` was explicitly rejected — it causes write errors when memory is full, turning cache pressure into order failures.

### Observability
- **Metrics** — Spring Boot Actuator → Prometheus (scraped every 5s). Grafana dashboard shows orders/sec, p95 latency, error rate, Kafka consumer lag.
- **Logs** — Structured JSON via Logback → Promtail → Loki. All containers labelled `stack=true` are auto-tailed. Loki → Tempo trace linking enabled via `traceId` field.
- **Traces** — OTel SDK → OTel Collector → Tempo. Full span from HTTP request through Kafka publish and SSE dispatch visible as a single trace in Grafana.

### Security — Traefik reverse proxy

All public-facing traffic enters through **Traefik on port `8888`**. Services do not implement authentication themselves — the proxy enforces it at the edge.

**What is in place:**
- **BasicAuth on the dashboard** — browser login prompt before the Angular app is served; credentials never reach the application layer
- **Rate limiting on the Order API** — token bucket at 100 req/s average, burst 50; returns `429 Too Many Requests` when exceeded, protecting the database from uncontrolled write spikes
- **Single entry point** — all admin UIs (Grafana, Kafka UI, pgAdmin, Prometheus, Redis Insight) routed through one port; direct container ports remain open as a fallback
- **Config-file routing** — all rules live in `infra/config/traefik/dynamic.yml` with zero labels on services; routes reload without restarting Traefik

### Performance tuning
Key settings applied to sustain 250 req/sec burst:

| Layer | Setting | Why |
|---|---|---|
| PostgreSQL | `synchronous_commit=off` | 3–5× write throughput; safe because the outbox is the durable log |
| PostgreSQL | `shared_buffers=512MB` | Hot order rows stay in memory |
| HikariCP | `maximumPoolSize=20` | Matches concurrent Tomcat threads under burst |
| Kafka | 6 partitions | Parallelism ceiling for consumers |
| OutboxPoller | `batchSize=500` | Single DB query publishes 500 events |
| Dashboard | `bufferTime(16ms)` | One animation frame — batches SSE events for smooth 60fps rendering |

---

## Failure Handling

### Kafka unavailable
Orders are still accepted and persisted to PostgreSQL. The outbox poller retries on a fixed interval — events accumulate in the `outbox_events` table and are published in order when Kafka recovers. No order is lost, no API error is surfaced to the caller.

### Service crash
On restart the outbox poller immediately scans for `published=false` events and replays them. Because Kafka producers use idempotent delivery and the notification service deduplicates on `orderId`, replayed events do not cause duplicate notifications.

### Duplicate orders
Three layers (described above in Design Decisions) ensure exactly-once semantics from the caller's perspective. A duplicate submission returns `200 OK` with the original order — indistinguishable from a successful first write.

### Redis unavailable
Redis is a cache, not the system of record. If Redis is down the service falls through to PostgreSQL for both idempotency checks and order reads. Latency increases but correctness is maintained. The DB unique constraint remains the hard guard against duplicates.

---

## Load Test

### Install k6

```bash
# macOS
brew install k6

# Linux
sudo snap install k6

# Windows
choco install k6
```

### Smoke test — quick sanity check (5 iterations)
Verifies the full lifecycle: create order → idempotent replay → check response codes.

```bash
cd test
k6 run smoke-test.js
```

### Load test — full throughput + burst

Three phases:
- **Warmup** — ramp 0 → 83 req/sec over 1 minute
- **Sustained** — hold 83 req/sec for 5 minutes (~5,000 orders/min)
- **Burst** — spike to 250 req/sec for 1 minute

Includes a 5% duplicate request rate to exercise idempotency under load.

> **Why direct port `:8080`?** The load test targets the Order Service directly, bypassing Traefik's 100 req/s rate limit. This tests raw service throughput — the limit exists to protect production traffic, not benchmark capacity. The Traefik gateway and rate limiting are verified separately.

```bash
cd test
k6 run order-load-test.js
# default BASE_URL=http://localhost:8080 — direct, no rate limiting
```

**What to watch while the test runs:**
- **Grafana → Stack Overview** (http://localhost:3010/grafana) — orders/sec, p95 latency, Kafka consumer lag
- **Dashboard** (http://localhost:4200) — live order stream with CREATED / FAILED counts
- **Kafka UI** (http://localhost:8090/kafka-ui) — consumer lag per partition

**Expected results:**

| Threshold | Target | Typical result |
|---|---|---|
| `http_req_failed` | < 1% | ~0% |
| `http_req_duration p(95)` | < 500ms | ~3–4ms |
| `duplicate_orders_rejected` | > 0 | ~5% of total |

---

## Conclusion

The platform demonstrates that the two-service architecture described in the brief — when combined with the right infrastructure choices — comfortably exceeds the 5,000 orders/minute target. The sustained load test at 83 req/sec (~5,000/min) produces sub-5ms p95 latency with 0% error rate. The 250 req/sec burst (3× the requirement) holds stable without degradation.

The key architectural choices — Transactional Outbox for guaranteed delivery, multi-layer idempotency for exactly-once semantics, SSE for real-time fan-out, and a full Prometheus/Loki/Tempo observability stack — address every non-functional requirement in the brief directly and demonstrably.

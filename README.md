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
| **Landing page** | [http://localhost:8888](http://localhost:8888) | — | open |
| **Dashboard** | [http://localhost:8888/dashboard](http://localhost:8888/dashboard) | [http://localhost:4200](http://localhost:4200) | Traefik: admin / admin@12 |
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

**Partition sizing rationale (target: 5,000 orders/minute)**

| Factor | Value | Derivation |
|---|---|---|
| Target rate | 83 orders/sec | 5,000 ÷ 60 |
| Avg payload | ~1.5 KB | JSON order object |
| Total throughput | ~125 KB/s | 83 × 1.5 KB — well within a single partition's 10–50 MB/s capacity |
| Throughput need | 1 partition | Not the bottleneck |
| Consumer pods | 6 | 1 partition per pod for full parallelism |
| **Chosen partitions** | **6** | Driver is consumer parallelism, not throughput |

`orderId` is used as the Kafka message key — same order always routes to the same partition, preserving event order (`CREATED → PAID → SHIPPED`) per order.

### Circuit Breaker — kafka-publisher
Wraps the Kafka publish call in `OutboxPoller`. If Kafka degrades, the circuit opens and the poller skips publishing — events accumulate safely in the outbox table and flush when Kafka recovers.

| Setting | Value | Why |
|---|---|---|
| `sliding-window-size` | 50 | ~600ms of data at 83 events/sec — avoids opening on a single transient blip |
| `minimum-number-of-calls` | 20 | No decision made until 20 calls observed — prevents false open at startup |
| `failure-rate-threshold` | 50% | Opens if ≥25 of last 50 calls fail (50 calls × 50% = 25) |
| `slow-call-duration-threshold` | 2s | Calls taking >2s count as failures — slow Kafka is as bad as failed Kafka |
| `slow-call-rate-threshold` | 50% | Opens if ≥50% of calls are slow, even if they eventually succeed |
| `wait-duration-in-open-state` | 10s | ~830 events queue in outbox during open window — DB holds them safely |
| `permitted-number-of-calls-in-half-open-state` | 5 | 5 test calls before deciding to close — more confident signal than 3 |

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

### Redis — allkeys-lru eviction
Redis is configured with `maxmemory-policy allkeys-lru`. Every key in this instance carries a TTL (`idem:*` at 24h, `order:*` at 30s) — SSE is handled in-memory via WebFlux Sinks, not Redis pub/sub. Since all keys have TTLs, `allkeys-lru` and `volatile-lru` behave identically today, but `allkeys-lru` is safer: any key accidentally stored without a TTL is still eligible for eviction rather than accumulating silently. `noeviction` was explicitly rejected — it causes write errors when memory is full, turning cache pressure into order failures. Early eviction before TTL expiry is safe because the DB is the correctness backstop for both idempotency (Layer 2 DB check) and order cache (re-fetchable on miss).

### Observability

| Signal | Pipeline | What it captures |
|---|---|---|
| Metrics | Actuator → Prometheus (every 5s) → Grafana | orders/sec, p95 latency, error rate, Kafka consumer lag, circuit breaker state |
| Logs | Logback JSON → Promtail → Loki → Grafana | All containers labelled `stack=true` auto-tailed; `traceId` field links log lines to Tempo traces |
| Traces | OTel SDK → OTel Collector → Tempo → Grafana | Full span: HTTP request → DB → Redis → Kafka publish → SSE dispatch |

**Trace sampling (OTel Collector — tail-based)**

Decision is made after the request completes, so errors and slow traces are never dropped.

| Policy | Rule | Behaviour |
|---|---|---|
| `errors` | HTTP status is ERROR or exception thrown | Always kept — every failure is recorded for debugging |
| `slow` | Response time exceeds 500ms | Always kept — latency spikes need full trace visibility |
| `sample` | Normal successful fast requests | 1% kept, 99% discarded — enough for statistical baselines |

At 83 req/sec this reduces Tempo write volume from ~415 spans/sec to ~6 spans/sec while retaining full visibility on every problem.

**Storage paths (host → container)**

| Component | Host path | Container path | Note |
|---|---|---|---|
| Prometheus | `infra/containers/prometheus/data` | `/prometheus` | Persisted — survives restart |
| Tempo | `infra/containers/tempo/data` | `/tmp/tempo` | Persisted — blocks + WAL + generator WAL |
| Loki | `infra/containers/loki/data` | `/tmp/loki` | Persisted — chunks, TSDB index, compactor all under this path |
| Promtail | — | — | No storage — tails live container logs and pushes to Loki |
| OTel Collector | — | — | No storage — receives spans and forwards to Tempo |

### Security — Traefik reverse proxy

All public-facing traffic enters through **Traefik on port `8888`**. Services do not implement authentication themselves — the proxy enforces it at the edge.

**What is in place:**
- **BasicAuth on the dashboard** — browser login prompt before the Angular app is served; credentials never reach the application layer
- **Rate limiting on the Order API** — token bucket at 100 req/s average, burst 50; returns `429 Too Many Requests` when exceeded, protecting the database from uncontrolled write spikes
- **Single entry point** — all admin UIs (Grafana, Kafka UI, pgAdmin, Prometheus, Redis Insight) routed through one port; direct container ports remain open as a fallback
- **Config-file routing** — all rules live in `infra/config/traefik/dynamic.yml` with zero labels on services; routes reload without restarting Traefik

### Performance tuning
Key settings applied to sustain 250 req/sec burst:

| Layer | Setting | Why | Production note |
|---|---|---|---|
| PostgreSQL | `synchronous_commit=off` | Skips WAL fsync before ACK — 3–5× write throughput gain | **Demo tradeoff only** — up to ~200ms of commits lost on hard crash. Production: keep `on` and use PgBouncer (removes connection overhead — the real bottleneck) + replica with `remote_write` (WAL reaches replica memory before ACK — full durability with read scaling) |
| PostgreSQL | `shared_buffers=512MB` | Frequently accessed order and outbox rows served from memory — reduces disk I/O on repeated reads | Increase proportionally with RAM; typical production value is 25% of total server memory |
| HikariCP | `maximumPoolSize=20` | Matches the number of concurrent Tomcat threads under burst — avoids connection starvation without over-provisioning PG connections | With PgBouncer in production, app-side pool can be raised freely; PgBouncer caps the real PG connections |
| Kafka | 6 partitions | One partition per notification-service pod — each pod gets a dedicated partition for full consumer parallelism | Raise partition count in step with pod count; partitions cannot be reduced once created |
| OutboxPoller | `batchSize=500` | Single `SELECT FOR UPDATE SKIP LOCKED` query fetches up to 500 events — one DB round-trip per poll cycle regardless of batch size | At 10× scale replace poller with Debezium (CDC) to eliminate batch lag entirely |
| Dashboard | `bufferTime(16ms)` | Batches incoming SSE events into one Angular render cycle — aligns with a 60fps display frame (1000ms ÷ 60 ≈ 16ms) | No change needed at scale — client-side only, independent of server throughput |

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

| Threshold | Target | Typical result | What it validates |
|---|---|---|---|
| `http_req_failed` | < 1% | ~0% | No 4xx/5xx errors under sustained load — DB, Kafka, and idempotency all healthy |
| `http_req_duration p(95)` | < 500ms | ~3–4ms | 95th percentile latency — Redis cache and HikariCP pool sized correctly |
| `duplicate_orders_rejected` | > 0 | ~5% of total | Idempotency layer correctly identifies and replays duplicate submissions |

---

## Production Scaling Path

Current design is validated at **5,000 orders/min (~83/sec)**. The architecture scales horizontally with targeted component upgrades — but each upgrade has its own ceiling. This section maps the bottlenecks, their replacements, and where the next limit sits.

**Scaling reference point:**

| Metric | Demo (current) | 10× scale | 100× scale |
|---|---|---|---|
| Orders/min | 5,000 | 50,000 | 500,000 |
| Orders/sec | 83 | 833 | 8,333 |
| DB writes/sec | ~170 | ~1,700 | ~17,000 |
| Kafka messages/sec | 83 | 833 | 8,333 |

**Bottlenecks and their replacements:**

| Component | Demo design | Bottleneck | Production replacement | Replacement ceiling |
|---|---|---|---|---|
| Outbox poller | Single-threaded, 1s poll, batch 500 | ~500 events/poll — accumulates lag under burst | **Debezium (CDC)** reads PostgreSQL WAL directly; zero polling lag, publishes instantly | Throughput tied to PostgreSQL WAL write rate; single connector per PG instance — partition Debezium topics for parallel consumers |
| PostgreSQL connections | HikariCP 20 per pod | 10 pods × 20 = 200 real connections; PG degrades above ~300–500 | **PgBouncer** multiplexes all app connections into a fixed real PG pool | PgBouncer itself bottlenecks at extreme scale — run multiple instances; PostgreSQL write throughput is the deeper ceiling (vertical scale or sharding) |
| Kafka partitions | 6 partitions | Max 6 concurrent consumers | Raise partition count to match consumer pod count | Partitions cannot be reduced once created; consumer group coordination adds overhead above ~200 partitions — at that point consider multiple topics |
| Notification fan-out | Random group-id — every pod processes every message | Pod count × message rate = Kafka read amplification | **Dedicated broadcast layer** — Redis Streams or Centrifugo decouples SSE fan-out from Kafka | Redis Cluster handles millions of pub/sub messages/sec; Centrifugo designed for millions of concurrent SSE connections |

**What does not need to change at any scale:**

| Component | Why it scales as-is | What changes at high scale |
|---|---|---|
| Transactional outbox pattern | `FOR UPDATE SKIP LOCKED` distributes work across any number of concurrent pollers with zero coordination | Replace polling with Debezium CDC to eliminate lag; pattern and schema stay identical |
| Three-layer idempotency | Redis + DB unique constraint are both horizontally scalable; no cross-pod coordination or shared lock needed | Redis Cluster for Redis tier; DB unique constraint works unchanged on any PostgreSQL topology |
| JDK 21 virtual threads | Carrier threads are not blocked on I/O — thousands of concurrent HTTP connections handled with a small OS thread pool | No change — virtual threads are the correct model for high-concurrency I/O workloads at any scale |
| Observability stack | Prometheus, Loki, and Tempo each scale independently of order throughput | Swap local filesystem storage for remote object storage (S3/GCS) on Tempo and Loki for unlimited retention |
| Schema | All bottlenecks are in infrastructure (connections, polling, fan-out) — the schema itself is unchanged | No schema migrations required at any scale |

> No single upgrade eliminates all limits — each replacement pushes the ceiling higher and introduces the next one. The path is additive: PgBouncer → Debezium → partition scaling → dedicated fan-out. The outbox pattern, idempotency design, and observability stack remain valid at every scale.

---

## Conclusion

The platform demonstrates that the two-service architecture described in the brief — when combined with the right infrastructure choices — comfortably exceeds the 5,000 orders/minute target. The sustained load test at 83 req/sec (~5,000/min) produces sub-5ms p95 latency with 0% error rate. The 250 req/sec burst (3× the requirement) holds stable without degradation.

The key architectural choices — Transactional Outbox for guaranteed delivery, multi-layer idempotency for exactly-once semantics, SSE for real-time fan-out, and a full Prometheus/Loki/Tempo observability stack — address every non-functional requirement in the brief directly and demonstrably. The scaling path beyond the demo target is documented in the section above — no schema changes are required at any scale, only infrastructure additions.

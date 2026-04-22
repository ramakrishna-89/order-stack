import http   from 'k6/http';
import { check, sleep } from 'k6';
import { b64encode } from 'k6/encoding';

// Smoke test — via Traefik on :8888
// Verifies:
//   BasicAuth on dashboard (401 without, 200 with)
//   Order lifecycle routed through Traefik → Order Service
//   Grafana + Kafka UI reachable via Traefik subpath routing
//   Idempotency works end-to-end through the proxy

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8888';
const AUTH     = `Basic ${b64encode('admin:admin@12')}`;

export const options = {
  vus:        1,
  iterations: 3,
  thresholds: {
    http_req_failed:   ['rate<0.01'],
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {

  // ── 1. Dashboard — no auth → 401 (expected, mark as non-failure) ────
  const noAuth = http.get(`${BASE_URL}/`, {
    tags:             { name: 'dashboard-noauth' },
    responseCallback: http.expectedStatuses(401),
  });
  check(noAuth, { 'dashboard: 401 without BasicAuth': (r) => r.status === 401 });

  // ── 2. Dashboard — BasicAuth → 200 ──────────────────────────────────
  const authed = http.get(`${BASE_URL}/`, {
    headers: { Authorization: AUTH },
    tags:    { name: 'dashboard-authed' },
  });
  check(authed, { 'dashboard: 200 with BasicAuth': (r) => r.status === 200 });

  // ── 3. Health check via Traefik (order-api router, no auth) ─────────
  const health = http.get(`${BASE_URL}/actuator/health`);
  check(health, {
    'actuator/health: 200':  (r) => r.status === 200,
    'order service UP':      (r) => {
      try { return JSON.parse(r.body).status === 'UP'; } catch { return false; }
    },
  });

  // ── 4. Create order via Traefik ──────────────────────────────────────
  const idempotencyKey = `traefik-smoke-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    customerId:  'CUST-TRAEFIK-01',
    amount:      '250.00',
    currency:    'SAR',
    description: 'Traefik smoke test order',
  });

  const create = http.post(`${BASE_URL}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'X-Idempotency-Key': idempotencyKey },
  });

  const created = check(create, {
    'POST /orders via Traefik: 201': (r) => r.status === 201,
    'status CREATED':                (r) => {
      try { return JSON.parse(r.body).status === 'CREATED'; } catch { return false; }
    },
    'has orderId':                   (r) => {
      try { return !!JSON.parse(r.body).id; } catch { return false; }
    },
  });

  if (!created) {
    console.error(`[FAIL] create via Traefik: status=${create.status} body=${create.body}`);
    return;
  }

  const orderId = JSON.parse(create.body).id;

  // ── 5. Wait for outbox → Kafka → PUBLISHED ───────────────────────────
  sleep(2);

  const getRes = http.get(`${BASE_URL}/orders/${orderId}`);
  check(getRes, {
    'GET /orders/{id} via Traefik: 200': (r) => r.status === 200,
    'status PUBLISHED':                  (r) => {
      try { return JSON.parse(r.body).status === 'PUBLISHED'; } catch { return false; }
    },
  });

  // ── 6. Idempotency replay via Traefik ────────────────────────────────
  const replay = http.post(`${BASE_URL}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'X-Idempotency-Key': idempotencyKey },
  });
  check(replay, {
    'duplicate via Traefik: 200':      (r) => r.status === 200,
    'duplicate returns same orderId':   (r) => {
      try { return JSON.parse(r.body).id === orderId; } catch { return false; }
    },
  });

  // ── 7. Grafana reachable via Traefik ─────────────────────────────────
  const grafana = http.get(`${BASE_URL}/grafana/api/health`);
  check(grafana, { 'Grafana /grafana via Traefik: 200': (r) => r.status === 200 });

  // ── 8. Kafka UI reachable via Traefik ────────────────────────────────
  const kafkaUi = http.get(`${BASE_URL}/kafka-ui`, { redirects: 3 });
  check(kafkaUi, { 'Kafka UI /kafka-ui via Traefik: 200': (r) => r.status === 200 });

  console.log(`[OK] iter=${__ITER} orderId=${orderId} CREATED→PUBLISHED→DUPLICATE  (all via Traefik :8888)`);
}

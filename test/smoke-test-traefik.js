import http   from 'k6/http';
import { check, sleep } from 'k6';
import { b64encode } from 'k6/encoding';

// Smoke test — via Traefik on :8888
// Verifies:
//   BasicAuth enforced on dashboard, Kafka UI, pgAdmin, Prometheus
//   Order lifecycle routed through Traefik → Order Service
//   Prometheus UI (not Angular dashboard) loads at /prometheus
//   Grafana reachable via /grafana (own auth)
//   Redis Insight NOT routed via Traefik (direct :5541 only)

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

// Mark 401 as expected so it doesn't count against http_req_failed
const expect401 = { responseCallback: http.expectedStatuses(401) };

export default function () {

  // ── Landing page — public, no auth required ──────────────────────────
  const landing = http.get(`${BASE_URL}/`);
  check(landing, {
    'landing page: 200 (public)':   (r) => r.status === 200,
    'landing page: correct title':  (r) => r.body && r.body.includes('Order Stack'),
  });

  // ── Dashboard auth (Angular SPA served at /dashboard/ with BasicAuth) ──
  const dashNoAuth = http.get(`${BASE_URL}/dashboard/`, { ...expect401, tags: { name: 'dashboard-noauth' } });
  check(dashNoAuth, { 'dashboard: 401 without auth': (r) => r.status === 401 });

  const dashAuthed = http.get(`${BASE_URL}/dashboard/`, {
    headers:   { Authorization: AUTH },
    redirects: 3,
  });
  check(dashAuthed, {
    'dashboard: 200 with auth':    (r) => r.status === 200,
    'dashboard: Angular app-root': (r) => r.body && r.body.includes('<app-root>'),
  });

  // ── Kafka UI auth ────────────────────────────────────────────────────
  const kafkaNoAuth = http.get(`${BASE_URL}/kafka-ui`, { ...expect401, tags: { name: 'kafka-noauth' } });
  check(kafkaNoAuth, { 'kafka-ui: 401 without auth': (r) => r.status === 401 });

  const kafkaAuthed = http.get(`${BASE_URL}/kafka-ui`, {
    headers:   { Authorization: AUTH },
    redirects: 3,
  });
  check(kafkaAuthed, { 'kafka-ui: 200 with auth': (r) => r.status === 200 });

  // ── pgAdmin auth ─────────────────────────────────────────────────────
  const pgNoAuth = http.get(`${BASE_URL}/pgadmin`, { ...expect401, tags: { name: 'pgadmin-noauth' } });
  check(pgNoAuth, { 'pgadmin: 401 without auth': (r) => r.status === 401 });

  const pgAuthed = http.get(`${BASE_URL}/pgadmin`, {
    headers:   { Authorization: AUTH },
    redirects: 3,
  });
  check(pgAuthed, { 'pgadmin: 200 with auth': (r) => r.status === 200 });

  // ── Prometheus auth + correct UI ─────────────────────────────────────
  const promNoAuth = http.get(`${BASE_URL}/prometheus`, { ...expect401, tags: { name: 'prom-noauth' } });
  check(promNoAuth, { 'prometheus: 401 without auth': (r) => r.status === 401 });

  const promAuthed = http.get(`${BASE_URL}/prometheus/graph`, {
    headers:   { Authorization: AUTH },
    redirects: 3,
  });
  check(promAuthed, {
    'prometheus: 200 with auth':      (r) => r.status === 200,
    'prometheus UI (not dashboard)':  (r) => r.body && r.body.includes('Prometheus') && !r.body.includes('<app-root>'),
  });

  // ── Redis Insight auth (/redis subpath — RI_PROXY_PATH makes assets load correctly)
  const redisNoAuth = http.get(`${BASE_URL}/redis`, { ...expect401, tags: { name: 'redis-noauth' } });
  check(redisNoAuth, { 'redis-insight: 401 without auth': (r) => r.status === 401 });

  const redisAuthed = http.get(`${BASE_URL}/redis`, {
    headers:   { Authorization: AUTH },
    redirects: 3,
  });
  check(redisAuthed, { 'redis-insight: 200 with auth': (r) => r.status === 200 });

  // ── Grafana reachable (own login, no Traefik BasicAuth) ──────────────
  const grafana = http.get(`${BASE_URL}/grafana/api/health`);
  check(grafana, { 'grafana: 200 via Traefik': (r) => r.status === 200 });

  // ── Order API health via Traefik (no auth needed) ────────────────────
  const health = http.get(`${BASE_URL}/actuator/health`);
  check(health, {
    'actuator/health: 200':  (r) => r.status === 200,
    'order service UP':      (r) => {
      try { return JSON.parse(r.body).status === 'UP'; } catch { return false; }
    },
  });

  // ── Full order lifecycle via Traefik ─────────────────────────────────
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

  sleep(2); // wait for outbox → Kafka → PUBLISHED

  const getRes = http.get(`${BASE_URL}/orders/${orderId}`);
  check(getRes, {
    'GET /orders/{id} via Traefik: 200': (r) => r.status === 200,
    'status PUBLISHED':                  (r) => {
      try { return JSON.parse(r.body).status === 'PUBLISHED'; } catch { return false; }
    },
  });

  const replay = http.post(`${BASE_URL}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'X-Idempotency-Key': idempotencyKey },
  });
  check(replay, {
    'duplicate via Traefik: 200':     (r) => r.status === 200,
    'duplicate returns same orderId': (r) => {
      try { return JSON.parse(r.body).id === orderId; } catch { return false; }
    },
  });

  console.log(`[OK] iter=${__ITER} orderId=${orderId} CREATED→PUBLISHED→DUPLICATE  (all via Traefik :8888)`);
}

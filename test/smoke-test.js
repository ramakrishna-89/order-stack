import http   from 'k6/http';
import { check, sleep } from 'k6';

// Smoke test — 5 iterations, 1 VU
// Verifies the full order lifecycle:
//   POST /orders  → 201  status=CREATED   (order saved, outbox written)
//   GET  /orders  → 200  status=PUBLISHED  (outbox poller confirmed Kafka delivery)
//   POST /orders  → 200  same orderId      (idempotency — DUPLICATE SSE event fires)

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus:        1,
  iterations: 5,
  thresholds: {
    http_req_failed:   ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
  },
};

export default function () {

  // ── 1. Health ────────────────────────────────────────────────────────
  const health = http.get(`${BASE_URL}/actuator/health`);
  check(health, { 'health OK': (r) => r.status === 200 });

  // ── 2. Create order ──────────────────────────────────────────────────
  const idempotencyKey = `smoke-${__VU}-${__ITER}-${Date.now()}`;
  const res = http.post(
    `${BASE_URL}/orders`,
    JSON.stringify({
      customerId:  'CUST-SMOKE-01',
      amount:      '999.00',
      currency:    'INR',
      description: 'Smoke test order',
    }),
    { headers: { 'Content-Type': 'application/json', 'X-Idempotency-Key': idempotencyKey } }
  );

  const created = check(res, {
    'order created (201)':  (r) => r.status === 201,
    'status is CREATED':    (r) => {
      try { return JSON.parse(r.body).status === 'CREATED'; } catch { return false; }
    },
    'has orderId':          (r) => {
      try { return !!JSON.parse(r.body).id; } catch { return false; }
    },
  });

  if (!created) {
    console.error(`[FAIL] create: status=${res.status} body=${res.body}`);
    return;
  }

  const orderId = JSON.parse(res.body).id;

  // ── 3. Wait for outbox → Kafka → PUBLISHED transition ────────────────
  // OutboxPoller runs every 1 s. After 2 s the status should be PUBLISHED.
  sleep(2);

  const get = http.get(`${BASE_URL}/orders/${orderId}`);
  check(get, {
    'GET order OK (200)':   (r) => r.status === 200,
    'status is PUBLISHED':  (r) => {
      try { return JSON.parse(r.body).status === 'PUBLISHED'; } catch { return false; }
    },
  });

  // ── 4. Duplicate replay — same key, must return existing order ────────
  const replay = http.post(
    `${BASE_URL}/orders`,
    JSON.stringify({
      customerId:  'CUST-SMOKE-01',
      amount:      '999.00',
      currency:    'INR',
      description: 'Smoke test order',
    }),
    { headers: { 'Content-Type': 'application/json', 'X-Idempotency-Key': idempotencyKey } }
  );

  check(replay, {
    'duplicate returns 200':          (r) => r.status === 200,
    'duplicate returns same orderId': (r) => {
      try { return JSON.parse(r.body).id === orderId; } catch { return false; }
    },
  });

  console.log(`[OK] iter=${__ITER} orderId=${orderId} CREATED→PUBLISHED→DUPLICATE`);
}

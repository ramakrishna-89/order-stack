import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ── Custom metrics ────────────────────────────────────────────────────────────
const duplicateRequests   = new Counter('duplicate_requests_sent');
const duplicateRejections = new Counter('duplicate_orders_rejected');
const orderCreated        = new Counter('orders_created');
const orderFailed         = new Counter('orders_failed');
const orderLatency        = new Trend('order_latency_ms', true);
const errorRate           = new Rate('error_rate');

// ── Config ────────────────────────────────────────────────────────────────────
const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const CUSTOMER_POOL_SIZE = 1000;   // simulate 1,000 unique customers
const DUPLICATE_RATE     = 0.05;   // 5% of requests reuse an existing key

// Pre-generate a fixed pool of idempotency keys that will be reused
// as "duplicate" requests — simulates client retries under network issues
const DUPLICATE_POOL = new SharedArray('duplicate-keys', function () {
  const keys = [];
  for (let i = 0; i < 200; i++) {
    keys.push(`dupe-idem-key-${i.toString().padStart(5, '0')}`);
  }
  return keys;
});

// ── Load profile ──────────────────────────────────────────────────────────────
//
//  RPS  ▲
//  250  │                    ████████████
//   83  │        ████████████            ████
//    0  └────────────────────────────────────► time
//        warm-up  sustained    burst    cool-down
//         1 min    5 min       30s       30s
//
export const options = {
  scenarios: {
    // Ramp from 0 → 83 RPS (5,000/min)
    warmup: {
      executor:        'ramping-arrival-rate',
      startRate:       0,
      timeUnit:        '1s',
      preAllocatedVUs: 50,
      maxVUs:          200,
      stages:          [{ duration: '1m', target: 83 }],
      startTime:       '0s',
    },
    // Hold at 83 RPS for 5 min — steady state
    sustained: {
      executor:        'constant-arrival-rate',
      rate:            83,
      timeUnit:        '1s',
      preAllocatedVUs: 50,
      maxVUs:          200,
      duration:        '5m',
      startTime:       '1m',
    },
    // Spike to 250 RPS (3x) for 30s — burst test
    burst: {
      executor:        'ramping-arrival-rate',
      startRate:       83,
      timeUnit:        '1s',
      preAllocatedVUs: 150,
      maxVUs:          500,
      stages: [
        { duration: '10s', target: 250 },   // ramp up to burst
        { duration: '30s', target: 250 },   // hold burst
        { duration: '20s', target: 0   },   // cool down
      ],
      startTime: '6m',
    },
  },

  // ── Pass/fail thresholds (shown in Grafana + k6 exit code) ─────────────────
  thresholds: {
    http_req_duration:          ['p(95)<500'],   // 95th percentile under 500ms
    http_req_failed:            ['rate<0.001'],  // error rate under 0.1%
    order_latency_ms:           ['p(99)<1000'],  // 99th percentile under 1s
    duplicate_orders_rejected:  ['count>0'],     // proof idempotency is working
  },
};

// ── Helpers ───────────────────────────────────────────────────────────────────
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

function randomCustomerId() {
  const n = Math.floor(Math.random() * CUSTOMER_POOL_SIZE) + 1;
  return `CUST-${n.toString().padStart(4, '0')}`;
}

function randomAmount() {
  // INR 100 – 50,000 (realistic order amounts)
  return (Math.random() * 49900 + 100).toFixed(2);
}

function pickIdempotencyKey() {
  if (Math.random() < DUPLICATE_RATE) {
    // Reuse a key from the fixed pool → triggers deduplication
    const key = DUPLICATE_POOL[Math.floor(Math.random() * DUPLICATE_POOL.length)];
    duplicateRequests.add(1);
    return { key, isDuplicate: true };
  }
  return { key: uuidv4(), isDuplicate: false };
}

// ── Main test function (runs for every virtual request) ───────────────────────
export default function () {
  const { key, isDuplicate } = pickIdempotencyKey();
  const customerId = randomCustomerId();

  const payload = JSON.stringify({
    customerId,
    amount:      randomAmount(),
    currency:    'INR',
    description: `Order from k6 load test`,
  });

  const params = {
    headers: {
      'Content-Type':       'application/json',
      'X-Idempotency-Key':  key,
    },
    tags: {
      scenario:    isDuplicate ? 'duplicate' : 'new-order',
    },
  };

  const res = http.post(`${BASE_URL}/orders`, payload, params);

  // ── Assertions ──────────────────────────────────────────────────────────────
  const ok = check(res, {
    'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
    'has orderId in body':  (r) => {
      try { return JSON.parse(r.body).id !== undefined; } catch { return false; }
    },
    'response time < 1s':   (r) => r.timings.duration < 1000,
  });

  // ── Record metrics ──────────────────────────────────────────────────────────
  orderLatency.add(res.timings.duration);
  errorRate.add(!ok);

  if (res.status === 200 || res.status === 201) {
    orderCreated.add(1);
    if (isDuplicate && res.status === 200) {
      duplicateRejections.add(1);  // 200 = existing order returned (deduped)
    }
  } else {
    orderFailed.add(1);
  }
}

// ── Lifecycle hooks ───────────────────────────────────────────────────────────
export function setup() {
  console.log(`Target: ${BASE_URL}`);
  console.log(`Duplicate rate: ${DUPLICATE_RATE * 100}%`);
  console.log(`Customer pool: ${CUSTOMER_POOL_SIZE} unique customers`);

  // Smoke check — fail fast if service is down
  const res = http.get(`${BASE_URL}/actuator/health`);
  if (res.status !== 200) {
    throw new Error(`Order Service not reachable at ${BASE_URL} (status: ${res.status})`);
  }
  console.log('Order Service health check passed — starting load test');
}

export function teardown(data) {
  console.log('Load test complete.');
}

export function handleSummary(data) {
  const p95 = data.metrics.http_req_duration?.values?.['p(95)'] || 0;
  const p99 = data.metrics.http_req_duration?.values?.['p(99)'] || 0;
  const errRate = (data.metrics.http_req_failed?.values?.rate || 0) * 100;
  const dupes = data.metrics.duplicate_orders_rejected?.values?.count || 0;
  const created = data.metrics.orders_created?.values?.count || 0;

  console.log('\n========= LOAD TEST SUMMARY =========');
  console.log(`Orders created:        ${created}`);
  console.log(`Duplicate rejections:  ${dupes}`);
  console.log(`p95 latency:           ${p95.toFixed(0)}ms  (threshold: <500ms)`);
  console.log(`p99 latency:           ${p99.toFixed(0)}ms  (threshold: <1000ms)`);
  console.log(`Error rate:            ${errRate.toFixed(3)}%  (threshold: <0.1%)`);
  console.log('=====================================\n');

  return {
    'stdout': JSON.stringify(data, null, 2),
  };
}

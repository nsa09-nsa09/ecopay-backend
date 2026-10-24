// EcoPay mixed-traffic load test (k6).
//
// SAFETY: this script must only ever run against an EcoPay instance whose payment provider is the
// in-memory MOCK gateway. It never talks to FreedomPay. It refuses to start unless
// ECOPAY_PROVIDER_IS_MOCK=yes is set and the base URL does not point at a FreedomPay host.
//
// Profiles (env PROFILE):
//   smoke  - 20 VUs, ~2 minutes: functional check of every request type.
//   load   - gradual ramp to 1000 VUs, sustained phase, ramp down (default).
//
// Every VU behaves like one browser session with think time between page views. Write actions
// (room create / join / payment intent) run at low frequency, like real users.
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const API = `${BASE}/api/v1`;
const PROFILE = __ENV.PROFILE || 'load';
const USERS = parseInt(__ENV.USERS || (PROFILE === 'smoke' ? '20' : '300'), 10);
const OWNERS = parseInt(__ENV.OWNERS || (PROFILE === 'smoke' ? '4' : '40'), 10);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const PASSWORD = 'LoadTest#2026';

if (__ENV.ECOPAY_PROVIDER_IS_MOCK !== 'yes' || /freedompay|paybox/i.test(BASE)) {
  throw new Error('Refusing to run: set ECOPAY_PROVIDER_IS_MOCK=yes and target a mock-provider instance.');
}

const stagesByProfile = {
  smoke: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 20 },
    { duration: '15s', target: 0 },
  ],
  load: [
    { duration: '2m', target: 100 },
    { duration: '3m', target: 400 },
    { duration: '3m', target: 700 },
    { duration: '3m', target: 1000 },
    { duration: '10m', target: 1000 }, // sustained phase
    { duration: '2m', target: 0 },
  ],
};

export const options = {
  setupTimeout: '10m',
  scenarios: {
    browsing: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: stagesByProfile[PROFILE] || stagesByProfile.load,
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    // Unexpected failures only: 4xx answers that are part of normal behaviour (room full, already
    // joined, rate-limited join) are declared expected per request.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<800', 'p(99)<2000'],
    'http_req_duration{kind:auth}': ['p(95)<1500'],
    'http_req_duration{kind:write}': ['p(95)<2000'],
    checks: ['rate>0.99'],
    // Informational sub-metrics so the summary attributes failures by status.
    'ecopay_unexpected_status{status:401}': ['count>=0'],
    'ecopay_unexpected_status{status:403}': ['count>=0'],
    'ecopay_unexpected_status{status:429}': ['count>=0'],
    'ecopay_unexpected_status{status:500}': ['count>=0'],
    'ecopay_unexpected_status{status:503}': ['count>=0'],
    'ecopay_unexpected_status{status:0}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const writeLatency = new Trend('ecopay_write_latency', true);
const intentsCreated = new Counter('ecopay_payment_intents_created');
const joinsCreated = new Counter('ecopay_room_joins_created');
// Unexpected statuses by endpoint, so a failed threshold can be attributed (401 vs 429 vs 5xx).
const unexpected = new Counter('ecopay_unexpected_status');

function track(res, name) {
  if (res.status < 200 || res.status >= 300) unexpected.add(1, { status: String(res.status), name });
}

// Each VU appears to come from its own client address through the trusted reverse proxy.
function clientIp(vu) {
  return `198.51.${Math.floor(vu / 250) % 250}.${(vu % 250) + 1}`;
}

function headers(token, vu, extra) {
  const h = Object.assign(
    { 'Content-Type': 'application/json', 'X-Forwarded-For': clientIp(vu) },
    extra || {},
  );
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

function email(prefix, i) {
  return `ecopay.lt.${RUN_ID}.${prefix}${i}@gmail.com`;
}

function register(prefix, i) {
  const res = http.post(
    `${API}/auth/register`,
    JSON.stringify({
      email: email(prefix, i),
      password: PASSWORD,
      displayName: `LT ${prefix}${i}`,
      termsAccepted: true,
    }),
    { headers: headers(null, 900000 + i), tags: { kind: 'auth', name: 'register' } },
  );
  check(res, { 'register 2xx': (r) => r.status >= 200 && r.status < 300 });
}

function login(prefix, i, vu) {
  const res = http.post(
    `${API}/auth/login`,
    JSON.stringify({ email: email(prefix, i), password: PASSWORD }),
    { headers: headers(null, vu), tags: { kind: 'auth', name: 'login' } },
  );
  check(res, { 'login 200': (r) => r.status === 200 });
  track(res, 'login');
  return res.status === 200 ? res.json('accessToken') : null;
}

function pickRoomTemplate() {
  // A seeded DIGITAL service/tariff with enough seats for the admin-configured room minimum.
  const res = http.get(`${API}/catalog/services`, { tags: { kind: 'read', name: 'services' } });
  const body = res.json();
  const services = Array.isArray(body) ? body : body.content || body.items || [];
  const ordered = services
    .filter((s) => s.providerType !== 'TELECOM')
    .sort((a, b) => (a.accessType === 'EMAIL' ? -1 : 0) - (b.accessType === 'EMAIL' ? -1 : 0));
  for (const s of ordered) {
    const t = http.get(`${API}/catalog/services/${s.id}/tariffs`, { tags: { kind: 'read', name: 'tariffs' } });
    if (t.status !== 200) continue;
    const tariffs = t.json();
    const list = Array.isArray(tariffs) ? tariffs : tariffs.content || tariffs.items || [];
    const tariff = list.find((x) => (x.maxMembers || 0) >= 5 && x.connectionType == null);
    if (tariff) return { serviceId: s.id, categoryId: s.categoryId, tariffPlanId: tariff.id };
  }
  return null;
}

export function setup() {
  // Owners: register, connect a payout card through the (mock) payout-card flow, create one room.
  const rooms = [];
  const template = pickRoomTemplate();
  if (!template) console.warn('no seeded tariff with >= 5 seats; write paths will be skipped');
  for (let i = 0; i < OWNERS; i++) {
    register('owner', i);
    const token = login('owner', i, 800000 + i);
    if (!token || !template) continue;
    // Owners need a verified phone; the local harness enables the dev bypass code (never in prod).
    const phone = `+7701${String(Date.now() % 1000).padStart(3, '0')}${String(i).padStart(4, '0')}`;
    http.post(`${API}/auth/phone/request-code`, JSON.stringify({ phone }), {
      headers: headers(token, 800000 + i),
      tags: { kind: 'auth', name: 'phone-request' },
    });
    http.post(`${API}/auth/phone/verify`, JSON.stringify({ phone, code: __ENV.PHONE_BYPASS_CODE || '000000' }), {
      headers: headers(token, 800000 + i),
      tags: { kind: 'auth', name: 'phone-verify' },
    });
    http.post(`${API}/payouts/methods/binding`, JSON.stringify({}), {
      headers: headers(token, 800000 + i),
      tags: { kind: 'write', name: 'payout-binding' },
    });
    const start = new Date(Date.now() + 60 * 24 * 3600 * 1000).toISOString().slice(0, 19);
    const res = http.post(
      `${API}/rooms`,
      JSON.stringify({
        serviceId: template.serviceId,
        tariffPlanId: template.tariffPlanId,
        categoryId: template.categoryId,
        roomType: 'DIGITAL',
        title: `Load test room ${RUN_ID}-${i}`,
        startDate: start,
      }),
      { headers: headers(token, 800000 + i), tags: { kind: 'write', name: 'room-create' } },
    );
    if (res.status === 201) {
      rooms.push(res.json('id'));
    } else if (i === 0) {
      console.warn(`room create failed: ${res.status} ${String(res.body).slice(0, 300)}`);
    }
  }
  for (let i = 0; i < USERS; i++) register('member', i);
  console.log(`setup: ${rooms.length} rooms, ${USERS} members`);
  return { rooms };
}

const session = { token: null, tokenAt: 0, joinedRoom: null };
// Access tokens live 15 min; the SPA refreshes before expiry, so does the virtual user.
const TOKEN_REFRESH_MS = 13 * 60 * 1000;

function readPublic(vu) {
  const params = { headers: headers(null, vu), tags: { kind: 'read' } };
  group('public pages', () => {
    const responses = http.batch([
      ['GET', `${API}/catalog/categories`, null, Object.assign({}, params, { tags: { kind: 'read', name: 'categories' } })],
      ['GET', `${API}/catalog/services`, null, Object.assign({}, params, { tags: { kind: 'read', name: 'services' } })],
      ['GET', `${API}/public/home-stats`, null, Object.assign({}, params, { tags: { kind: 'read', name: 'home-stats' } })],
    ]);
    responses.forEach((r) => check(r, { 'public 200': (x) => x.status === 200 }));
  });
}

function searchAndRoom(vu, data) {
  const params = { headers: headers(null, vu) };
  const q = ['net', 'spot', 'you', 'apple', 'kino'][vu % 5];
  const search = http.get(`${API}/catalog/search?q=${q}`, Object.assign({}, params, { tags: { kind: 'read', name: 'search' } }));
  check(search, { 'search 200': (r) => r.status === 200 });
  const list = http.get(
    `${API}/rooms?page=0&size=20&serviceId=2&sortBy=newest`,
    Object.assign({}, params, { tags: { kind: 'read', name: 'room-list' } }),
  );
  check(list, { 'room list 200': (r) => r.status === 200 });
  if (data.rooms.length > 0) {
    const id = data.rooms[vu % data.rooms.length];
    const detail = http.get(`${API}/rooms/${id}`, Object.assign({}, params, { tags: { kind: 'read', name: 'room-detail' } }));
    check(detail, { 'room detail 200': (r) => r.status === 200 });
  }
}

function dashboard(vu) {
  const params = { headers: headers(session.token, vu) };
  const responses = http.batch([
    ['GET', `${API}/users/me`, null, Object.assign({}, params, { tags: { kind: 'read', name: 'me' } })],
    ['GET', `${API}/users/me/dashboard`, null, Object.assign({}, params, { tags: { kind: 'read', name: 'dashboard' } })],
    ['GET', `${API}/rooms/joined`, null, Object.assign({}, params, { tags: { kind: 'read', name: 'joined' } })],
    ['GET', `${API}/notifications/unread-count`, null, Object.assign({}, params, { tags: { kind: 'read', name: 'unread' } })],
  ]);
  responses.forEach((r) => {
    check(r, { 'authenticated 200': (x) => x.status === 200 });
    track(r, r.request.url.replace(/^.*\/api\/v1/, ''));
  });
  if (responses.some((r) => r.status === 401)) session.token = null;
  if (Math.random() < 0.3) {
    const n = http.get(`${API}/notifications?page=0&size=20`, Object.assign({}, params, { tags: { kind: 'read', name: 'notifications' } }));
    check(n, { 'notifications 200': (r) => r.status === 200 });
    const h = http.get(`${API}/payments/history`, Object.assign({}, params, { tags: { kind: 'read', name: 'payment-history' } }));
    check(h, { 'history 200': (r) => r.status === 200 });
  }
}

function visit(vu, path) {
  // Light analytics: the frontend coalesces pings, so roughly one per page group.
  const res = http.post(`${API}/analytics/visit`, JSON.stringify({ path }), {
    headers: headers(session.token, vu),
    tags: { kind: 'write', name: 'visit' },
    responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 429),
  });
  check(res, { 'visit accepted or throttled': (r) => r.status < 300 || r.status === 429 });
}

function joinAndPay(vu, data) {
  if (session.joinedRoom || data.rooms.length === 0) return;
  const roomId = data.rooms[Math.floor(Math.random() * data.rooms.length)];
  const t0 = Date.now();
  const join = http.post(
    `${API}/rooms/${roomId}/members`,
    JSON.stringify({ consentAccepted: true, identifierValue: `lt.${RUN_ID}.${vu}@gmail.com` }),
    {
      headers: headers(session.token, vu),
      tags: { kind: 'write', name: 'room-join' },
      // Full room / already a member / join throttle are normal outcomes, not failures.
      responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 400, 409, 429),
    },
  );
  if (join.status !== 201) return;
  joinsCreated.add(1);
  session.joinedRoom = roomId;
  const memberId = join.json('id');
  const intent = http.post(
    `${API}/payments/members/${memberId}/intent`,
    JSON.stringify({ idempotencyKey: `lt-${RUN_ID}-${vu}-${memberId}` }),
    {
      headers: headers(session.token, vu),
      tags: { kind: 'write', name: 'payment-intent' },
      responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 409),
    },
  );
  check(intent, { 'intent created': (r) => r.status === 200 || r.status === 201 || r.status === 409 });
  if (intent.status < 300) intentsCreated.add(1);
  writeLatency.add(Date.now() - t0);
}

export default function (data) {
  const vu = exec.vu.idInTest;
  if (!session.token || Date.now() - session.tokenAt > TOKEN_REFRESH_MS) {
    session.token = login('member', vu % USERS, vu);
    session.tokenAt = Date.now();
  }

  readPublic(vu);
  visit(vu, '/');
  sleep(2 + Math.random() * 4);

  searchAndRoom(vu, data);
  visit(vu, '/rooms');
  sleep(3 + Math.random() * 5);

  if (session.token) {
    dashboard(vu);
    visit(vu, '/profile');
    // Low-frequency write path: ~3% of iterations, each VU joins at most once.
    if (Math.random() < 0.03) joinAndPay(vu, data);
  }
  sleep(4 + Math.random() * 6);
}

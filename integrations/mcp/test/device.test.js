import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:https';
import { createServer as createTcpServer } from 'node:net';
import { X509Certificate, createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { callDevice, loadDeviceConfig, validateDeviceUrl, REQUEST_LIMIT, RESPONSE_LIMIT, TIMEOUT_MS } from '../src/device.js';
import { deviceSchemas } from '../src/device-validation.js';
import { key, cert } from './device-fixture.js';

const token = 'x'.repeat(43);
const certificateSha256 = createHash('sha256').update(new X509Certificate(cert).raw).digest('hex');
const baseConfig = { version: 1, baseUrl: 'https://127.0.0.1:39393', certificateSha256, token };
async function fixture(t, handler) {
  const requests = [];
  let applicationBytes = 0;
  const sockets = new Set();
  const server = createServer({ key, cert }, async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const received = { headers: req.headers, method: req.method, url: req.url, body: JSON.parse(Buffer.concat(chunks).toString()) };
    requests.push(received);
    if (handler) handler(received, res);
    else res.end(JSON.stringify({ result: { timeZone: 'Pacific/Auckland', utc: '2026-11-05T03:00:00.000Z', localIso: '2026-11-05T16:00:00.000+13:00', local: 'Thursday', weekday: 'Thursday' } }));
  });
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  server.on('secureConnection', socket => socket.on('data', bytes => { applicationBytes += bytes.length; }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); });
  return { config: { ...baseConfig, baseUrl: `https://127.0.0.1:${server.address().port}` }, requests, get applicationBytes() { return applicationBytes; } };
}
async function directory(t) {
  const path = await mkdtemp(join(tmpdir(), 'sunlith-device-test-'));
  t.after(() => rm(path, { recursive: true, force: true }));
  return path;
}

test('private IPv4 URL validation rejects public, normalized and decorated endpoints', () => {
  for (const host of ['10.0.0.1', '127.0.0.1', '172.16.0.1', '172.31.255.255', '192.168.1.2', '100.64.0.1', '100.127.255.254']) assert.doesNotThrow(() => validateDeviceUrl(`https://${host}:39393`));
  for (const url of ['http://127.0.0.1:39393', 'https://8.8.8.8', 'https://100.63.255.255', 'https://100.128.0.1', 'https://172.15.0.1', 'https://172.32.0.1', 'https://0.0.0.0', 'https://localhost:39393', 'https://[::1]:39393', 'https://127.1', 'https://2130706433', 'https://0x7f000001', 'https://127.000.000.001', 'https://user:pass@127.0.0.1', 'https://127.0.0.1/v1/tools', 'https://127.0.0.1/../', 'https://127.0.0.1?token=secret', 'https://127.0.0.1#secret', 'https://127.0.0.1:0', 'https://127.0.0.1:65536', ' https://127.0.0.1', 'https://127.0.0.1\n']) assert.throws(() => validateDeviceUrl(url), url);
});

test('configuration requires a strict bounded private file outside the repository', async t => {
  const dir = await directory(t);
  const path = join(dir, 'device.json');
  const write = data => writeFile(path, data, { mode: 0o600 });
  await write(JSON.stringify(baseConfig));
  assert.deepEqual(await loadDeviceConfig(path), baseConfig);
  await assert.rejects(loadDeviceConfig('relative.json'), /absolute path/);
  await assert.rejects(loadDeviceConfig(fileURLToPath(new URL('../src/index.js', import.meta.url))), /outside/);
  await assert.rejects(loadDeviceConfig(dir), /Cannot read/);
  for (const data of ['{', '[]', JSON.stringify({ ...baseConfig, token: 'secret' }), JSON.stringify({ ...baseConfig, certificateSha256: certificateSha256.toUpperCase() }), JSON.stringify({ ...baseConfig, version: 2 }), JSON.stringify({ ...baseConfig, extra: true }), 'x'.repeat(4097)]) {
    await write(data);
    await assert.rejects(loadDeviceConfig(path));
  }
  await write(Buffer.from([0xff, 0xfe]));
  await assert.rejects(loadDeviceConfig(path), /Cannot read/);
  const link = join(dir, 'linked.json');
  try { await symlink(fileURLToPath(new URL('../src/index.js', import.meta.url)), link, 'file'); }
  catch (error) { if (!['EPERM', 'EACCES'].includes(error.code)) throw error; return; }
  await assert.rejects(loadDeviceConfig(link), /Cannot read/);
});

test('real TLS sends authenticated JSON only after the certificate pin matches', async t => {
  const device = await fixture(t);
  const result = await callDevice(device.config, 'get_current_time');
  assert.equal(result.timeZone, 'Pacific/Auckland');
  assert.equal(device.requests.length, 1);
  assert.equal(device.requests[0].method, 'POST');
  assert.equal(device.requests[0].url, '/v1/tools');
  assert.equal(device.requests[0].headers.authorization, `Bearer ${token}`);
  assert.deepEqual(device.requests[0].body, { tool: 'get_current_time', arguments: {} });
  assert.ok(device.applicationBytes > 0);
});

test('a wrong pin receives zero HTTP bytes, including zero bearer headers', async t => {
  const device = await fixture(t);
  await assert.rejects(callDevice({ ...device.config, certificateSha256: '0'.repeat(64) }, 'get_current_time'), /certificate does not match/);
  assert.equal(device.requests.length, 0);
  assert.equal(device.applicationBytes, 0);
});

test('deadline covers stalled HTTPS responses and a stalled TLS handshake', async t => {
  assert.equal(TIMEOUT_MS, 10_000);
  const device = await fixture(t, () => {});
  await assert.rejects(callDevice(device.config, 'get_current_time', {}, { timeoutMs: 75 }), /timed out/);
  const sockets = new Set();
  const server = createTcpServer(socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); });
  await assert.rejects(callDevice({ ...baseConfig, baseUrl: `https://127.0.0.1:${server.address().port}` }, 'get_current_time', {}, { timeoutMs: 75 }), /timed out/);
});

test('remote errors are useful but never echo server text, addresses or authorization', async t => {
  for (const [status, code, expected] of [[400, 'INVALID_REQUEST', /times, recurrence/], [401, 'UNAUTHORIZED', /Pair/], [403, 'READ_ONLY', /read-only/], [404, 'NOT_FOUND', /not found/], [409, 'STALE_ETAG', /latest etag/], [409, 'SERIES_SCOPE_REQUIRED', /whole series/], [413, 'PAYLOAD_TOO_LARGE', /too large/], [503, 'UNAVAILABLE', /unavailable/]]) {
    const device = await fixture(t, (_, res) => { res.statusCode = status; res.end(JSON.stringify({ error: { code, message: `Bearer ${token} https://10.1.2.3` } })); });
    await assert.rejects(callDevice(device.config, 'get_current_time'), error => {
      assert.match(error.message, expected);
      assert.equal(error.message.includes(token), false);
      assert.equal(error.message.includes('10.1.2.3'), false);
      return true;
    });
  }
  for (const [status, code, expected] of [[400, 'bad_request', /times, recurrence/], [401, 'unauthorized', /Pair/], [403, 'calendar_not_allowed', /not allowed/], [404, 'not_found', /not found/], [409, 'revision_mismatch', /latest etag/], [409, 'operation_conflict', /operationId/], [422, 'blocked', /cannot safely change/], [429, 'rate_limited', /Wait briefly/], [413, 'too_large', /too large/], [503, 'permission_missing', /Calendar access/], [500, 'internal', /could not complete/]]) {
    const device = await fixture(t, (_, res) => { res.statusCode = status; res.end(JSON.stringify({ error: { code, message: token } })); });
    await assert.rejects(callDevice(device.config, 'get_current_time'), error => { assert.match(error.message, expected); assert.equal(error.message.includes(token), false); return true; });
  }
  const blocked = await fixture(t, (_, res) => { res.statusCode = 422; res.end(JSON.stringify({ error: { code: 'blocked', reason: 'guests', message: token } })); });
  await assert.rejects(callDevice(blocked.config, 'get_current_time'), /has guests/);
  const series = await fixture(t, (_, res) => { res.statusCode = 400; res.end(JSON.stringify({ error: { code: 'bad_request', reason: 'series_scope_required', message: token } })); });
  await assert.rejects(callDevice(series.config, 'get_current_time'), /whole series/);
});

test('redirects, malformed and ambiguous response envelopes are refused', async t => {
  for (const body of ['not json', '[]', '{}', '{"result":null}', '{"result":[]}', '{"result":{},"error":{}}', Buffer.from([0xff])]) {
    const device = await fixture(t, (_, res) => res.end(body));
    await assert.rejects(callDevice(device.config, 'get_current_time'), /invalid response/);
  }
  const destination = await fixture(t);
  const redirect = await fixture(t, (_, res) => { res.statusCode = 302; res.setHeader('location', destination.config.baseUrl); res.end('{}'); });
  await assert.rejects(callDevice(redirect.config, 'get_current_time'), /Redirects are not followed/);
  assert.equal(destination.requests.length, 0);
});

test('response limits cover declared and streamed body sizes', async t => {
  assert.equal(RESPONSE_LIMIT, 2 * 1024 * 1024);
  for (const declared of [true, false]) {
    const device = await fixture(t, (_, res) => {
      if (declared) res.setHeader('content-length', RESPONSE_LIMIT + 1);
      res.end('x'.repeat(RESPONSE_LIMIT + 1));
    });
    await assert.rejects(callDevice(device.config, 'get_current_time'), /too large/);
  }
});

test('private ICS files are bounded, read as UTF8, and sent without the desktop path', async t => {
  const dir = await directory(t);
  const path = join(dir, 'calendar.ics');
  const ics = 'BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Électricité\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n';
  await writeFile(path, ics, { mode: 0o600 });
  const device = await fixture(t, (_, res) => res.end(JSON.stringify({ result: { imported: 215, duplicates: 0, skipped: 0, messages: [] } })));
  const result = await callDevice(device.config, 'import_calendar_file', { calendarId: '42', filePath: path, confirm: true });
  assert.equal(result.imported, 215);
  assert.deepEqual(device.requests[0].body, { tool: 'import_calendar', arguments: { calendarId: '42', ics, confirm: true } });
  await writeFile(path, 'x'.repeat(256 * 1024 + 1));
  await assert.rejects(callDevice(device.config, 'import_calendar_file', { calendarId: '42', filePath: path, confirm: true }), /size limit/);
  await writeFile(path, Buffer.from([0xff]));
  await assert.rejects(callDevice(device.config, 'import_calendar_file', { calendarId: '42', filePath: path, confirm: true }), /UTF-8/);
  await writeFile(path, '\0'.repeat(100_000));
  assert.equal(REQUEST_LIMIT, 512 * 1024);
  await assert.rejects(callDevice(device.config, 'import_calendar_file', { calendarId: '42', filePath: path, confirm: true }), /too large/);
  assert.equal(device.requests.length, 1);
});

test('direct schemas reject Google-only inputs, arbitrary fields and individual-instance edits', () => {
  const range = { calendarId: '42', timeMin: '2026-11-01T00:00:00Z', timeMax: '2026-12-01T00:00:00Z' };
  assert.deepEqual(deviceSchemas.get_current_time.parse({}), {});
  assert.deepEqual(deviceSchemas.list_events.parse(range).eventTypes, ['default']);
  assert.equal(deviceSchemas.list_events.safeParse({ ...range, pageToken: '12' }).success, true);
  assert.equal(deviceSchemas.list_events.safeParse({ ...range, pageToken: '2147483647' }).success, true);
  assert.equal(deviceSchemas.list_events.safeParse({ ...range, pageToken: '2147483648' }).success, false);
  for (const extra of [{ calendarId: 'primary' }, { eventTypes: ['focusTime'] }, { eventTypes: ['default', 'default'] }, { pageToken: '-1' }, { pageToken: 'opaque' }, { pageToken: '9999999999999999' }, { attendees: ['someone'] }, { singleEvents: false }]) assert.equal(deviceSchemas.list_events.safeParse({ ...range, ...extra }).success, false);
  assert.equal(deviceSchemas.get_event.safeParse({ calendarId: '42', eventId: '123_20261104' }).success, false);
  assert.equal(deviceSchemas.get_event.safeParse({ calendarId: '9223372036854775808', eventId: '1' }).success, false);
  const deletion = { calendarId: '42', eventId: '1', etag: 'v1', confirm: true };
  assert.equal(deviceSchemas.delete_event.safeParse({ ...deletion, seriesScope: 'series' }).success, true);
  assert.equal(deviceSchemas.delete_event.safeParse({ ...deletion, seriesScope: 'occurrence' }).success, false);
  assert.equal(deviceSchemas.delete_event.safeParse({ ...deletion, confirm: false }).success, false);
  assert.equal(deviceSchemas.update_event.safeParse({ calendarId: '42', eventId: '1', etag: 'v1', seriesScope: 'series' }).success, false);
  assert.equal(deviceSchemas.import_calendar_file.safeParse({ calendarId: '42', filePath: 'relative.ics', confirm: true }).success, false);
});

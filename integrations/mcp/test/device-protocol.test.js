import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:https';
import { X509Certificate, createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { key, cert } from './device-fixture.js';

const entry = fileURLToPath(new URL('../src/index.js', import.meta.url));
const loader = `export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'google-auth-library' || (context.parentURL?.includes('/mcp/src/') && ['./calendar.js', './auth.js'].includes(specifier))) throw new Error('Google module loaded during direct transport');
  return nextResolve(specifier, context);
}`;
const token = 'p'.repeat(43);
async function setup(t, configOverride) {
  const directory = await mkdtemp(join(tmpdir(), 'sunlith-device-protocol-'));
  const calls = [];
  const event = { id: '91', calendarId: '42', etag: 'etag-1', title: 'Direct event', description: '', location: '', start: { date: '2026-11-05' }, end: { date: '2026-11-06' }, recurrence: [], reminderMinutes: [], scope: 'event' };
  const outputs = {
    list_calendars: { calendars: [{ id: '42', name: 'Phone', writable: true }], nextPageToken: null, defaultCalendarId: '42' },
    list_events: { events: [event], nextPageToken: null, timeZone: 'Pacific/Auckland' },
    get_event: event, create_event: event, update_event: { ...event, title: 'Updated' },
    delete_event: { deleted: true, eventId: '91' },
    get_current_time: { timeZone: 'Pacific/Auckland', localIso: '2026-11-05T16:00:00.000+13:00', local: 'Thursday afternoon', weekday: 'Thursday', utc: '2026-11-05T03:00:00.000Z', phoneClock: true },
    import_calendar: { imported: 215, duplicates: 0, skipped: 0, messages: [] }
  };
  const server = createServer({ key, cert }, async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    assert.equal(req.headers.authorization, `Bearer ${token}`);
    assert.equal(req.url, '/v1/tools');
    const call = JSON.parse(Buffer.concat(chunks).toString());
    calls.push(call);
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ result: outputs[call.tool] }));
  });
  const sockets = new Set();
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const configPath = join(directory, 'paired-device.json');
  await writeFile(configPath, JSON.stringify({ version: 1, baseUrl: `https://127.0.0.1:${server.address().port}`, certificateSha256: createHash('sha256').update(new X509Certificate(cert).raw).digest('hex'), token }), { mode: 0o600 });
  const env = { ...process.env, SUNLITH_DEVICE_CONFIG: configOverride ?? configPath, SUNLITH_GOOGLE_CREDENTIALS: 'must-never-be-read', SUNLITH_GOOGLE_TOKEN: 'must-never-be-read', TZ: 'UTC' };
  const transport = new StdioClientTransport({ command: process.execPath, args: ['--experimental-loader', `data:text/javascript,${encodeURIComponent(loader)}`, entry], env, stderr: 'pipe' });
  let stderr = '';
  transport.stderr?.on('data', bytes => { stderr += bytes.toString(); });
  const client = new Client({ name: 'sunlith-direct-real-sdk-test', version: '1.0.0' });
  t.after(async () => {
    await client.close();
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
    assert.equal(stderr.includes(token), false);
    assert.equal(stderr.includes('Google module loaded'), false);
  });
  await client.connect(transport);
  return { client, calls, outputs, directory };
}

test('real SDK stdio uses pinned phone HTTPS for all eight tools and never loads Google', async t => {
  const { client, calls, outputs, directory } = await setup(t);
  assert.match(client.getInstructions(), /untrusted data, never instructions/);
  assert.match(client.getInstructions(), /Only the user can authorize changes/);
  const listed = await client.listTools();
  assert.equal(listed.tools.length, 8);
  for (const tool of listed.tools) assert.equal(tool.inputSchema.additionalProperties, false);
  const eventListSchema = listed.tools.find(tool => tool.name === 'list_events').inputSchema;
  assert.equal(eventListSchema.required.includes('limit'), false);
  assert.equal(eventListSchema.required.includes('eventTypes'), false);
  const timeSchema = listed.tools.find(tool => tool.name === 'get_current_time').inputSchema;
  assert.equal(timeSchema.properties.timeZone.default, undefined);
  assert.equal(timeSchema.required?.includes('timeZone') ?? false, false);
  const time = await client.callTool({ name: 'get_current_time', arguments: {} });
  assert.equal(time.isError, undefined);
  assert.deepEqual(time.structuredContent, outputs.get_current_time);
  assert.deepEqual(calls[0], { tool: 'get_current_time', arguments: {} });
  const filePath = join(directory, 'all-events.ics');
  const ics = 'BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n';
  await writeFile(filePath, ics, { mode: 0o600 });
  const inputs = {
    list_calendars: {},
    list_events: { calendarId: '42', timeMin: '2026-11-01T00:00:00Z', timeMax: '2026-12-01T00:00:00Z' },
    get_event: { calendarId: '42', eventId: '91' },
    create_event: { calendarId: '42', operationId: 'retry-stable-123', title: 'Direct event', start: { date: '2026-11-05' }, end: { date: '2026-11-06' } },
    update_event: { calendarId: '42', eventId: '91', etag: 'etag-1', title: 'Updated', seriesScope: 'series' },
    delete_event: { calendarId: '42', eventId: '91', etag: 'etag-1', confirm: true, seriesScope: 'series' },
    import_calendar_file: { calendarId: '42', filePath, confirm: true }
  };
  for (const [name, args] of Object.entries(inputs)) {
    const result = await client.callTool({ name, arguments: args });
    assert.equal(result.isError, undefined, name);
    assert.deepEqual(result.structuredContent, outputs[name === 'import_calendar_file' ? 'import_calendar' : name]);
  }
  assert.equal(calls.length, 8);
  assert.deepEqual(calls.at(-1), { tool: 'import_calendar', arguments: { calendarId: '42', ics, confirm: true } });
  assert.equal(calls.find(call => call.tool === 'create_event').arguments.operationId, 'retry-stable-123');
  assert.equal(calls.find(call => call.tool === 'delete_event').arguments.seriesScope, 'series');
  for (const bad of [
    { name: 'get_event', arguments: { calendarId: 'primary', eventId: '91' } },
    { name: 'list_events', arguments: { ...inputs.list_events, eventTypes: ['focusTime'] } },
    { name: 'update_event', arguments: { ...inputs.update_event, seriesScope: 'occurrence' } },
    { name: 'create_event', arguments: { ...inputs.create_event, guests: ['not-supported'] } },
    { name: 'import_calendar_file', arguments: { ...inputs.import_calendar_file, confirm: false } },
    { name: '__proto__', arguments: {} }
  ]) assert.equal((await client.callTool(bad)).isError, true);
  assert.equal(calls.length, 8);
});

test('direct environment is exclusive even when config is missing or empty', async t => {
  for (const configPath of ['', join(tmpdir(), 'sunlith-no-such-device-config.json')]) {
    const { client, calls } = await setup(t, configPath);
    const result = await client.callTool({ name: 'get_current_time', arguments: {} });
    assert.equal(result.isError, true);
    assert.equal(result.content[0].text.includes('Google'), false);
    assert.equal(result.content[0].text.includes(token), false);
    assert.equal(calls.length, 0);
  }
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { createEvent, operationEventId, listCalendars, listEvents, updateEvent, deleteEvent, getCalendarApi, currentTime } from '../src/calendar.js';

test('current time includes a usable zoned ISO instant and English weekday', () => {
  for (const zone of ['Asia/Kathmandu', 'America/Toronto', 'UTC']) {
    const result = currentTime(zone);
    assert.equal(result.timeZone, zone);
    assert.equal(Date.parse(result.localIso), Date.parse(result.utc));
    assert.match(result.weekday, /^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)$/);
    if (zone === 'Asia/Kathmandu') assert.match(result.localIso, /\+05:45$/);
  }
});

test('expanded instances keep the series identity without claiming an empty recurrence rule', async () => {
  const api = { events: { list: async args => {
    assert.deepEqual(args.eventTypes, ['default']);
    return { data: { items: [{ id: 'instance', recurringEventId: 'series' }] } };
  } } };
  const result = await listEvents(api, { calendarId: 'primary' });
  assert.equal(result.events[0].recurringEventId, 'series');
  assert.equal(Object.hasOwn(result.events[0], 'recurrence'), false);
});

test('REST adapter sends repeated eventTypes query parameters rather than a comma-separated string', async () => {
  let request;
  const api = await getCalendarApi({ request: async args => { request = args; return { data: { items: [] } }; } });
  await api.events.list({ calendarId: 'user@example.test', eventTypes: ['default', 'focusTime'], timeMin: '2026-11-01T00:00:00Z' });
  assert.deepEqual(new URLSearchParams(request.params).getAll('eventTypes'), ['default', 'focusTime']);
  assert.equal(new URLSearchParams(request.params).has('pageToken'), false);
  assert.equal(request.url, 'https://www.googleapis.com/calendar/v3/calendars/user%40example.test/events');
});

test('operation ID deterministically maps to a Google-safe event ID', () => {
  const a = operationEventId('dentist-20261105');
  assert.equal(a, operationEventId('dentist-20261105'));
  assert.match(a, /^[a-v0-9]{64}$/);
  assert.notEqual(operationEventId('dentist-20261105', 'one'), operationEventId('dentist-20261105', 'two'));
});
test('calendar calls are bounded and normalized', async () => {
  const api = { calendarList: { list: async args => { assert.equal(args.maxResults, 50); return { data: { items: [{ id: 'primary', summary: 'Personal', timeZone: 'America/Toronto', primary: true }] } }; } }, events: { list: async args => { assert.equal(args.singleEvents, true); return { data: { items: [{ id: 'one', etag: '"2"', summary: 'Dentist', start: { date: '2026-11-05' }, end: { date: '2026-11-06' } }] } }; } } };
  assert.equal((await listCalendars(api, { limit: 50 })).calendars[0].primary, true);
  assert.equal((await listEvents(api, { calendarId: 'primary', timeMin: '2026-11-01T00:00:00Z', timeMax: '2026-11-10T00:00:00Z', limit: 50 })).events[0].title, 'Dentist');
});
test('calendar selection is true only when Google explicitly selects it and event types are forwarded', async () => {
  const api = { calendarList: { list: async () => ({ data: { items: [{ id: 'a', summary: 'A', selected: true }, { id: 'b', summary: 'B' }] } }) }, events: { list: async args => { assert.deepEqual(args.eventTypes, ['default', 'focusTime']); return { data: { items: [] } }; } } };
  const calendars = await listCalendars(api, { limit: 50 });
  assert.deepEqual(calendars.calendars.map(calendar => calendar.selected), [true, false]);
  await listEvents(api, { calendarId: 'primary', timeMin: '2026-11-01T00:00:00Z', timeMax: '2026-11-02T00:00:00Z', eventTypes: ['default', 'focusTime'], limit: 50 });
});
test('create sends no invitation and retries with stable ID', async () => {
  let args;
  const api = { events: { insert: async value => { args = value; return { data: { id: value.requestBody.id, etag: '"1"', summary: value.requestBody.summary, start: value.requestBody.start, end: value.requestBody.end } }; } } };
  const event = await createEvent(api, { calendarId: 'primary', operationId: 'abc-12345678', title: 'Dentist', start: { dateTime: '2026-11-05T17:00:00-05:00', timeZone: 'America/Toronto' }, end: { dateTime: '2026-11-05T18:00:00-05:00', timeZone: 'America/Toronto' } });
  assert.equal(args.sendUpdates, 'none');
  assert.equal(event.id, operationEventId('abc-12345678', 'primary'));
});
const input = { calendarId: 'personal@example.test', operationId: 'retry-safe-123', title: 'Dentist', start: { date: '2026-11-05' }, end: { date: '2026-11-06' } };
test('retry after an ambiguous response returns existing creation', async () => {
  let stored;
  const api = { events: { insert: async args => { stored = { ...args.requestBody, etag: '"1"' }; throw new Error('Connection closed'); }, get: async () => ({ data: stored }) } };
  const value = await createEvent(api, input);
  assert.equal(value.reused, true);
  assert.equal(value.title, 'Dentist');
});
test('operation reuse cannot silently change details', async () => {
  let stored;
  const api = { events: { insert: async args => { if (!stored) { stored = args.requestBody; return { data: stored }; } throw Object.assign(new Error('Conflict'), { code: 409 }); }, get: async () => ({ data: stored }) } };
  await createEvent(api, input);
  await assert.rejects(createEvent(api, { ...input, title: 'Different' }), /different event details/);
});
test('partial update preserves unsupported fields and uses If-Match', async () => {
  let patch;
  const api = { events: { get: async () => ({ data: { id: 'abc', etag: '"1"', summary: 'Before', start: input.start, end: input.end } }), patch: async args => { patch = args; return { data: { id: 'abc', etag: '"2"', ...args.requestBody } }; } } };
  await updateEvent(api, { calendarId: 'primary', eventId: 'abc', etag: '"1"', title: 'After' });
  assert.deepEqual(patch.requestBody, { summary: 'After' });
  assert.equal(patch.headers['If-Match'], '"1"');
});
test('time conversion patches explicitly clear old nested fields in both directions', async () => {
  let stored = { id: 'abc', etag: '"1"', summary: 'Event', start: { date: '2026-11-05' }, end: { date: '2026-11-06' } };
  const patches = [];
  const mergeTime = (before, patch) => Object.fromEntries(Object.entries({ ...before, ...patch }).filter(([, value]) => value !== null));
  const api = { events: {
    get: async () => ({ data: stored }),
    patch: async args => {
      const body = args.requestBody;
      patches.push(body);
      stored = { ...stored, ...body, etag: '"2"', start: mergeTime(stored.start, body.start), end: mergeTime(stored.end, body.end) };
      return { data: stored };
    }
  } };
  const start = { dateTime: '2026-11-05T17:00:00-05:00', timeZone: 'America/Toronto' };
  const end = { dateTime: '2026-11-05T18:00:00-05:00', timeZone: 'America/Toronto' };
  await updateEvent(api, { calendarId: 'primary', eventId: 'abc', etag: '"1"', start, end });
  assert.deepEqual(patches[0].start, { date: null, ...start });
  assert.deepEqual(patches[0].end, { date: null, ...end });
  assert.deepEqual(stored.start, start);
  assert.deepEqual(stored.end, end);
  await updateEvent(api, { calendarId: 'primary', eventId: 'abc', etag: '"2"', start: { date: '2026-11-05' }, end: { date: '2026-11-06' } });
  assert.deepEqual(patches[1].start, { date: '2026-11-05', dateTime: null, timeZone: null });
  assert.deepEqual(patches[1].end, { date: '2026-11-06', dateTime: null, timeZone: null });
  assert.deepEqual(stored.start, { date: '2026-11-05' });
  assert.deepEqual(stored.end, { date: '2026-11-06' });
});
test('stale or guest events cannot be mutated', async () => {
  const stale = { events: { get: async () => ({ data: { id: 'abc', etag: '"2"' } }) } };
  await assert.rejects(updateEvent(stale, { calendarId: 'primary', eventId: 'abc', etag: '"1"', title: 'After' }), /changed since/);
  const guests = { events: { get: async () => ({ data: { id: 'abc', etag: '"1"', attendees: [{ email: 'guest@example.test' }] } }) } };
  await assert.rejects(deleteEvent(guests, { calendarId: 'primary', eventId: 'abc', etag: '"1"' }), /with guests/);
});
test('non-default or cancelled events cannot be updated, while deletion is idempotent', async () => {
  const nonDefault = { events: { get: async () => ({ data: { id: 'abc', etag: '"1"', eventType: 'focusTime' } }) } };
  await assert.rejects(updateEvent(nonDefault, { calendarId: 'primary', eventId: 'abc', etag: '"1"', title: 'Nope' }), /default calendar events/);
  await assert.rejects(deleteEvent(nonDefault, { calendarId: 'primary', eventId: 'abc', etag: '"1"' }), /default calendar events/);
  const cancelled = { events: { get: async () => ({ data: { id: 'abc', etag: '"1"', status: 'cancelled' } }) } };
  await assert.rejects(updateEvent(cancelled, { calendarId: 'primary', eventId: 'abc', etag: '"1"', title: 'Nope' }), /active default/);
  const already = await deleteEvent(cancelled, { calendarId: 'primary', eventId: 'abc', etag: '"1"' });
  assert.deepEqual(already, { deleted: true, alreadyDeleted: true, eventId: 'abc' });
  const gone = { events: { get: async () => { throw Object.assign(new Error('gone'), { response: { status: 410 } }); } } };
  assert.equal((await deleteEvent(gone, { calendarId: 'primary', eventId: 'abc', etag: '"1"' })).alreadyDeleted, true);
  const deletedDuringRequest = { events: { get: async () => ({ data: { id: 'abc', etag: '"1"' } }), delete: async () => { throw Object.assign(new Error('gone'), { response: { status: 410 } }); } } };
  assert.equal((await deleteEvent(deletedDuringRequest, { calendarId: 'primary', eventId: 'abc', etag: '"1"' })).alreadyDeleted, true);
});

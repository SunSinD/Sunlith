import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('actual Android CalendarProvider through pinned HTTPS and stdio MCP', {
  skip: !process.env.SUNLITH_ANDROID_TEST_METADATA,
  timeout: 120_000
}, async () => {
  const metadata = JSON.parse(await readFile(process.env.SUNLITH_ANDROID_TEST_METADATA, 'utf8'));
  const calendarId = String(metadata.calendarId);
  const excludedCalendarId = String(metadata.excludedCalendarId);
  const directory = await mkdtemp(join(tmpdir(), 'sunlith-android-integration-'));
  const client = new Client({ name: 'sunlith-android-verifier', version: '1.0.0' });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [fileURLToPath(new URL('../src/index.js', import.meta.url))],
    env: { ...process.env }, stderr: 'pipe'
  });
  transport.stderr?.resume();
  const created = [];
  async function call(name, args = {}) {
    const result = await client.callTool({ name, arguments: args });
    assert.equal(Boolean(result.isError), false, `${name} unexpectedly failed`);
    return result.structuredContent;
  }
  async function rejected(name, args) {
    const result = await client.callTool({ name, arguments: args });
    assert.equal(result.isError, true, `${name} should refuse unsafe input`);
    return result.content[0].text;
  }
  try {
    await client.connect(transport);
    assert.equal((await client.listTools()).tools.length, 8);
    const clock = await call('get_current_time');
    assert.ok(clock.timeZone && clock.localIso);
    const calendars = await call('list_calendars');
    assert.deepEqual(calendars.calendars.map(c => c.id), [calendarId]);
    await rejected('list_events', { calendarId: excludedCalendarId, timeMin: '2026-11-01T00:00:00Z', timeMax: '2026-12-01T00:00:00Z' });
    await rejected('get_event', { calendarId, eventId: String(metadata.excludedEventId) });
    const input = {
      calendarId, operationId: randomUUID(), title: 'Private bridge verification',
      start: { dateTime: '2026-11-05T17:00:00-05:00', timeZone: 'America/Toronto' },
      end: { dateTime: '2026-11-05T18:00:00-05:00', timeZone: 'America/Toronto' },
      reminderMinutes: [15]
    };
    const event = await call('create_event', input);
    created.push(event.id);
    assert.equal(event.reused, false);
    assert.deepEqual(event.start, input.start);
    assert.deepEqual(event.reminderMinutes, [15]);
    const again = await call('create_event', input);
    assert.equal(again.id, event.id);
    assert.equal(again.reused, true);
    await rejected('create_event', { ...input, title: 'Conflicting retry' });
    const updated = await call('update_event', { calendarId, eventId: event.id, etag: event.etag, title: 'Changed through desktop', reminderMinutes: [] });
    assert.notEqual(updated.etag, event.etag);
    assert.deepEqual(updated.reminderMinutes, []);
    await rejected('update_event', { calendarId, eventId: event.id, etag: event.etag, title: 'Stale update' });
    await rejected('delete_event', { calendarId, eventId: event.id, etag: event.etag, confirm: true });
    const listed = await call('list_events', { calendarId, timeMin: '2026-11-01T00:00:00Z', timeMax: '2026-12-01T00:00:00Z', query: 'Changed through desktop' });
    assert.equal(listed.events.length, 1);
    assert.equal(listed.events[0].id, event.id);
    const series = await call('create_event', {
      ...input, operationId: randomUUID(), title: 'Repeating bridge verification',
      start: { date: '2026-11-06' }, end: { date: '2026-11-07' },
      recurrence: ['RRULE:FREQ=WEEKLY;COUNT=3'], reminderMinutes: []
    });
    created.push(series.id);
    assert.match(await rejected('update_event', { calendarId, eventId: series.id, etag: series.etag, title: 'Missing series scope' }), /whole series/);
    const changedSeries = await call('update_event', { calendarId, eventId: series.id, etag: series.etag, seriesScope: 'series', title: 'Updated repeating verification' });
    assert.equal(changedSeries.scope, 'series');
    assert.deepEqual(changedSeries.start, { date: '2026-11-06' });
    const filePath = join(directory, 'bridge-test.ics');
    await writeFile(filePath, [
      'BEGIN:VCALENDAR', 'VERSION:2.0', 'BEGIN:VEVENT', `UID:${randomUUID()}@sunlith-test`,
      'DTSTAMP:20261104T120000Z', 'DTSTART:20261108T150000Z', 'DTEND:20261108T160000Z',
      'SUMMARY:Imported bridge verification', 'BEGIN:VALARM', 'ACTION:DISPLAY',
      'TRIGGER:-PT15M', 'DESCRIPTION:Reminder', 'END:VALARM', 'END:VEVENT', 'END:VCALENDAR', ''
    ].join('\r\n'), { mode: 0o600 });
    const imported = await call('import_calendar_file', { calendarId, filePath, confirm: true });
    assert.equal(imported.imported, 1);
    assert.equal(imported.skipped, 0);
    assert.equal(imported.atomic, false);
    const repeatedImport = await call('import_calendar_file', { calendarId, filePath, confirm: true });
    assert.equal(repeatedImport.imported, 0);
    assert.equal(repeatedImport.duplicates, 1);
    if (process.env.SUNLITH_ANDROID_IMPORT_FIXTURE) {
      const expected = Number(process.env.SUNLITH_ANDROID_IMPORT_COUNT);
      assert.ok(Number.isInteger(expected) && expected > 0);
      const privateImport = { calendarId, filePath: process.env.SUNLITH_ANDROID_IMPORT_FIXTURE, confirm: true };
      const report = await call('import_calendar_file', privateImport);
      assert.equal(report.imported, expected);
      assert.equal(report.duplicates, 0);
      assert.equal(report.skipped, 0);
      const retry = await call('import_calendar_file', privateImport);
      assert.equal(retry.imported, 0);
      assert.equal(retry.duplicates, expected);
      assert.equal(retry.skipped, 0);
    }
    for (const id of created) {
      const latest = await call('get_event', { calendarId, eventId: id });
      const deleted = await call('delete_event', { calendarId, eventId: id, etag: latest.etag, seriesScope: 'series', confirm: true });
      assert.equal(deleted.deleted, true);
    }
    created.length = 0;
    await rejected('get_event', { calendarId, eventId: event.id });
    await rejected('create_event', input);
  } finally {
    for (const id of created) {
      try {
        const latest = await call('get_event', { calendarId, eventId: id });
        await call('delete_event', { calendarId, eventId: id, etag: latest.etag, seriesScope: 'series', confirm: true });
      } catch { /* The isolated Android fixture deletes its entire test calendar. */ }
    }
    await client.close();
    await rm(directory, { recursive: true, force: true });
  }
});

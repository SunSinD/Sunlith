import test from 'node:test';
import assert from 'node:assert/strict';
import { schemas, validateTiming } from '../src/validation.js';

const start = { dateTime: '2026-11-05T17:00:00-05:00', timeZone: 'America/Toronto' };
const end = { dateTime: '2026-11-05T18:00:00-05:00', timeZone: 'America/Toronto' };
test('valid event requires bounded operation and RFC3339 times', () => {
  const parsed = schemas.create_event.safeParse({ calendarId: 'primary', operationId: 'dentist-20261105', title: 'Dentist', start, end });
  assert.equal(parsed.success, true);
});
test('all-day end is exclusive and mixed modes are rejected', () => {
  assert.doesNotThrow(() => validateTiming({ date: '2026-11-05' }, { date: '2026-11-06' }));
  assert.throws(() => validateTiming({ date: '2026-11-05' }, end));
  assert.throws(() => validateTiming({ date: '2026-11-06' }, { date: '2026-11-06' }));
});
test('event listing cannot exceed one year', () => {
  const parsed = schemas.list_events.safeParse({ calendarId: 'primary', timeMin: '2026-01-01T00:00:00Z', timeMax: '2027-02-01T00:00:00Z' });
  assert.equal(parsed.success, false);
});
test('list windows accept UTC independently of the event timezone and default to ordinary events', () => {
  const value = schemas.list_events.parse({ calendarId: 'primary', timeMin: '2026-11-01T00:00:00Z', timeMax: '2026-11-02T00:00:00Z' });
  assert.deepEqual(value.eventTypes, ['default']);
  assert.equal(value.timeMin, '2026-11-01T00:00:00Z');
});
test('delete requires explicit confirmation', () => {
  assert.equal(schemas.delete_event.safeParse({ calendarId: 'primary', eventId: 'abc', etag: '"1"' }).success, false);
});
test('impossible dates, unzoned times, unknown fields and invalid timezones fail', () => {
  const value = { calendarId: 'primary', operationId: 'dentist-20261105', title: 'Dentist', start, end };
  assert.equal(schemas.create_event.safeParse({ ...value, start: { date: '2026-02-30' }, end: { date: '2026-03-02' } }).success, false);
  assert.equal(schemas.create_event.safeParse({ ...value, start: { dateTime: '2026-11-05T17:00:00', timeZone: 'America/Toronto' } }).success, false);
  assert.equal(schemas.create_event.safeParse({ ...value, attendees: ['guest@example.test'] }).success, false);
  assert.equal(schemas.create_event.safeParse({ ...value, start: { ...start, timeZone: 'Moon/Crater' } }).success, false);
});
test('updates cannot change just one end of the time range', () => {
  assert.equal(schemas.update_event.safeParse({ calendarId: 'primary', eventId: 'abc', etag: '"1"', start }).success, false);
});
test('recurring timed events require an explicit consistent zone', () => {
  assert.throws(() => validateTiming({ dateTime: start.dateTime }, { dateTime: end.dateTime }, ['RRULE:FREQ=WEEKLY']), /explicit IANA/);
});
test('zoned event offsets reject DST gaps and mismatches while accepting both fold offsets', () => {
  const value = { calendarId: 'primary', operationId: 'dst-check-123', title: 'DST', start: { dateTime: '2026-11-01T01:30:00-04:00', timeZone: 'America/New_York' }, end: { dateTime: '2026-11-01T02:30:00-05:00', timeZone: 'America/New_York' } };
  assert.equal(schemas.create_event.safeParse(value).success, true);
  assert.equal(schemas.create_event.safeParse({ ...value, start: { dateTime: '2026-11-01T01:30:00-05:00', timeZone: 'America/New_York' }, end: { dateTime: '2026-11-01T02:30:00-05:00', timeZone: 'America/New_York' } }).success, true);
  assert.equal(schemas.create_event.safeParse({ ...value, start: { dateTime: '2026-03-08T02:30:00-05:00', timeZone: 'America/New_York' }, end: { dateTime: '2026-03-08T03:30:00-04:00', timeZone: 'America/New_York' } }).success, false);
  assert.equal(schemas.create_event.safeParse({ ...value, start: { dateTime: '2026-11-01T01:30:00+00:00', timeZone: 'America/New_York' } }).success, false);
});

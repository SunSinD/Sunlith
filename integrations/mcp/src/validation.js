import { z } from 'zod';

export function isTimeZone(value) {
  try { new Intl.DateTimeFormat('en', { timeZone: value }); return true; }
  catch { return false; }
}

function offsetMinutes(value) {
  if (value.endsWith('Z')) return 0;
  const match = value.match(/([+-])(\d{2}):(\d{2})$/);
  if (!match) return null;
  return (match[1] === '-' ? -1 : 1) * (Number(match[2]) * 60 + Number(match[3]));
}

function sameLocalClock(value, zone) {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  if (!match) return false;
  const expected = match.slice(1).map(Number);
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value));
  const actual = ['year', 'month', 'day', 'hour', 'minute', 'second'].map(name => Number(parts.find(part => part.type === name)?.value));
  return expected.every((part, index) => part === actual[index]);
}

export function isValidZonedDateTime(value, zone) {
  try {
    const offset = offsetMinutes(value);
    return offset !== null && sameLocalClock(value, zone);
  } catch { return false; }
}

export function isDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
}

const date = z.string().refine(isDate, 'Use a real date in YYYY-MM-DD format.');
export const dateTime = z.string().max(40).regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/, 'Use RFC3339 with seconds and a timezone offset.').refine(value => {
  if (!isDate(value.slice(0, 10))) return false;
  const parts = value.match(/T(\d{2}):(\d{2}):(\d{2})/);
  return Number(parts[1]) < 24 && Number(parts[2]) < 60 && Number(parts[3]) < 60 && Number.isFinite(Date.parse(value));
}, 'Use a valid date and time.');
export const timeZone = z.string().min(1).max(100).refine(isTimeZone, 'Use an IANA timezone, such as America/Toronto.');
const timed = z.object({ dateTime, timeZone }).strict().superRefine((value, ctx) => {
  if (!isValidZonedDateTime(value.dateTime, value.timeZone)) ctx.addIssue({ code: 'custom', path: ['dateTime'], message: 'The UTC offset must be valid for this IANA timezone at this local time. Check DST gaps and offsets.' });
});
const allDay = z.object({ date }).strict();
export const eventTime = z.union([timed, allDay]);
export const calendarId = z.string().min(1).max(512).refine(value => !/[\x00-\x1f]/.test(value), 'Invalid calendar ID.');
export const eventId = z.string().min(1).max(1024).regex(/^[a-zA-Z0-9_\-]+$/, 'Use the exact Google event ID returned by a tool.');
export const etag = z.string().min(1).max(512).refine(value => !/[\r\n]/.test(value), 'Invalid etag.');

export const recurrence = z.array(z.string().max(500).regex(/^RRULE:FREQ=(?:DAILY|WEEKLY|MONTHLY|YEARLY)(?:;[A-Z]+=[A-Z0-9,+\-]+)*$/, 'Only RRULE with DAILY, WEEKLY, MONTHLY, or YEARLY frequency is supported.')).max(1);
export const reminders = z.array(z.number().int().min(0).max(40320)).max(5);

export const eventFields = {
  title: z.string().trim().min(1).max(200),
  description: z.string().max(5000).optional(),
  location: z.string().max(1000).optional(),
  start: eventTime,
  end: eventTime.describe('End is exclusive. For a one-day all-day event, use the following date.'),
  recurrence: recurrence.optional(),
  reminderMinutes: reminders.optional()
};

export const schemas = {
  list_calendars: z.object({ pageToken: z.string().max(2048).optional(), limit: z.number().int().min(1).max(100).default(50) }).strict(),
  list_events: z.object({
    calendarId,
    timeMin: dateTime.describe('Exclusive lower bound on event end: only events ending after this instant. UTC or any valid RFC3339 offset is accepted.'),
    timeMax: dateTime.describe('Exclusive upper bound on event start: only events starting before this instant.'),
    query: z.string().max(300).optional(),
    pageToken: z.string().max(2048).optional(),
    limit: z.number().int().min(1).max(100).default(50),
    eventTypes: z.array(z.enum(['default', 'outOfOffice', 'focusTime', 'workingLocation', 'fromGmail'])).min(1).max(5).default(['default']).describe('Event types to include. Mutation tools only support the default event type.')
  }).strict().superRefine((value, ctx) => {
    const range = Date.parse(value.timeMax) - Date.parse(value.timeMin);
    if (range <= 0 || range > 366 * 86400000) ctx.addIssue({ code: 'custom', message: 'The time range must be positive and no longer than 366 days.' });
  }),
  get_event: z.object({ calendarId, eventId }).strict(),
  create_event: z.object({
    calendarId,
    operationId: z.string().min(8).max(128).regex(/^[A-Za-z0-9._-]+$/).describe('A unique operation ID. Reuse the SAME ID and details when retrying this creation.'),
    ...eventFields
  }).strict(),
  update_event: z.object({
    calendarId, eventId, etag,
    title: eventFields.title.optional(),
    description: eventFields.description,
    location: eventFields.location,
    start: eventTime.optional(),
    end: eventTime.optional(),
    recurrence: recurrence.optional(),
    reminderMinutes: reminders.optional()
  }).strict().superRefine((value, ctx) => {
    if ((value.start === undefined) !== (value.end === undefined)) ctx.addIssue({ code: 'custom', message: 'Provide both start and end when changing event time.' });
    if (!['title', 'description', 'location', 'start', 'recurrence', 'reminderMinutes'].some(key => value[key] !== undefined)) ctx.addIssue({ code: 'custom', message: 'Provide at least one field to update.' });
  }),
  delete_event: z.object({ calendarId, eventId, etag, confirm: z.literal(true).describe('Only true after the user requests cancellation/deletion of this exact event.') }).strict(),
  get_current_time: z.object({ timeZone: timeZone.default(() => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC') }).strict()
};

export function validateTiming(start, end, rules) {
  if (('date' in start) !== ('date' in end)) throw new Error('Start and end must both be all-day dates or timed values.');
  const startMs = Date.parse(start.date ?? start.dateTime);
  const endMs = Date.parse(end.date ?? end.dateTime);
  if (endMs <= startMs) throw new Error('End must be after start; all-day end dates are exclusive.');
  if (rules?.length && 'dateTime' in start && (!start.timeZone || !end.timeZone || start.timeZone !== end.timeZone)) throw new Error('Recurring start and end must use the same explicit IANA timezone.');
}

import { z } from 'zod';
import { isAbsolute } from 'node:path';
import { schemas, dateTime, timeZone, eventFields, eventTime, recurrence, reminders, etag, validateTiming } from './validation.js';

const id = z.string().regex(/^[1-9][0-9]{0,18}$/, 'Use the exact positive decimal device ID returned by Sunlith.').refine(value => /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n, 'Device ID is too large.');
const pageToken = z.string().max(10).regex(/^(0|[1-9][0-9]*)$/, 'Use the numeric page token returned by Sunlith.').refine(value => Number.isSafeInteger(Number(value)) && Number(value) <= 2147483647, 'Page token is too large.').optional();
const limit = z.number().int().min(1).max(100).default(50);
const seriesScope = z.literal('series').optional().describe('Required to change or delete a repeating series. Listing returns occurrences with the same master ID; individual occurrences cannot be changed.');
function timing(value, ctx) {
  if (!value.start || !value.end) return;
  try { validateTiming(value.start, value.end, value.recurrence); }
  catch { ctx.addIssue({ code: 'custom', message: 'Start and end must have matching types, a positive duration, and matching timezones for recurrence.' }); }
}

export const deviceSchemas = {
  list_calendars: z.object({ pageToken, limit }).strict(),
  list_events: z.object({
    calendarId: id, timeMin: dateTime, timeMax: dateTime,
    query: z.string().max(300).optional(), pageToken, limit,
    eventTypes: z.array(z.literal('default')).length(1).default(['default'])
  }).strict().superRefine((value, ctx) => {
    const range = Date.parse(value.timeMax) - Date.parse(value.timeMin);
    if (range <= 0 || range > 366 * 86400000) ctx.addIssue({ code: 'custom', message: 'The time range must be positive and no longer than 366 days.' });
  }),
  get_event: z.object({ calendarId: id, eventId: id }).strict(),
  create_event: z.object({ calendarId: id, operationId: schemas.create_event.shape.operationId, ...eventFields }).strict().superRefine(timing),
  update_event: z.object({
    calendarId: id, eventId: id, etag, seriesScope,
    title: eventFields.title.optional(), description: eventFields.description, location: eventFields.location,
    start: eventTime.optional(), end: eventTime.optional(), recurrence: recurrence.optional(), reminderMinutes: reminders.optional()
  }).strict().superRefine((value, ctx) => {
    if ((value.start === undefined) !== (value.end === undefined)) ctx.addIssue({ code: 'custom', message: 'Provide both start and end when changing event time.' });
    if (!['title', 'description', 'location', 'start', 'recurrence', 'reminderMinutes'].some(key => value[key] !== undefined)) ctx.addIssue({ code: 'custom', message: 'Provide at least one field to update.' });
    timing(value, ctx);
  }),
  delete_event: z.object({ calendarId: id, eventId: id, etag, seriesScope, confirm: z.literal(true) }).strict(),
  get_current_time: z.object({ timeZone: timeZone.optional().describe('Omit to use the phone timezone and clock.') }).strict(),
  import_calendar_file: z.object({ calendarId: id, filePath: z.string().max(4096).refine(isAbsolute, 'Use an absolute path to a private ICS file outside the repository.'), confirm: z.literal(true) }).strict()
};

export const deviceDescriptions = {
  list_calendars: 'List calendars allowed by the paired Sunlith phone. Calendar IDs are decimal strings; use defaultCalendarId if available.',
  list_events: 'List phone events ending after timeMin and starting before timeMax. Repeating occurrences share their master event ID. Individual occurrences cannot be changed; get_event returns the master. Only default eventTypes are supported.',
  get_event: 'Read a phone event or repeating master by its exact decimal ID. Read the current etag before editing.',
  create_event: 'Create an event directly on the paired phone. Reuse the same operationId and details for safe retries. No guests or invitation emails.',
  update_event: 'Update a phone event with its current etag. Repeating events require seriesScope:"series" and change the whole series. Individual occurrences cannot be changed.',
  delete_event: 'Delete an exact phone event with its current etag and confirm:true after the user requests it. Repeating events require seriesScope:"series" and delete the whole series.',
  get_current_time: 'Read the phone clock, current local and UTC time. Omitting timeZone uses the phone timezone.',
  import_calendar_file: 'Import a complete private local ICS file into an allowed phone calendar. Use an absolute filePath outside the repository and confirm:true after the user requests the import. Returns imported, duplicate and skipped counts; review every skipped entry.'
};

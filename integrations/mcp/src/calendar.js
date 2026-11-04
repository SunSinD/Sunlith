import { createHash } from 'node:crypto';
import { loadClient } from './auth.js';
import { validateTiming } from './validation.js';

export const defaultCalendar = 'primary';
const API = 'https://www.googleapis.com/calendar/v3';
function query(params) {
  const result = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    for (const item of Array.isArray(value) ? value : [value]) result.append(key, String(item));
  }
  return result;
}
export async function getCalendarApi(client) {
  const auth = client ?? await loadClient();
  async function request(method, path, params = {}, data, headers = {}) {
    const response = await auth.request({ url: `${API}${path}`, method, params: query(params), data, headers, timeout: 20000, retry: false });
    return { data: response.data };
  }
  return {
    calendarList: { list: args => request('GET', '/users/me/calendarList', { maxResults: args.maxResults, pageToken: args.pageToken, showHidden: args.showHidden }) },
    events: {
      list: args => request('GET', `/calendars/${encodeURIComponent(args.calendarId)}/events`, { timeMin: args.timeMin, timeMax: args.timeMax, q: args.q, maxResults: args.maxResults, pageToken: args.pageToken, singleEvents: args.singleEvents, orderBy: args.orderBy, showDeleted: args.showDeleted, eventTypes: args.eventTypes }),
      get: args => request('GET', `/calendars/${encodeURIComponent(args.calendarId)}/events/${encodeURIComponent(args.eventId)}`),
      insert: args => request('POST', `/calendars/${encodeURIComponent(args.calendarId)}/events`, { sendUpdates: args.sendUpdates }, args.requestBody),
      patch: args => request('PATCH', `/calendars/${encodeURIComponent(args.calendarId)}/events/${encodeURIComponent(args.eventId)}`, { sendUpdates: args.sendUpdates }, args.requestBody, args.headers),
      delete: args => request('DELETE', `/calendars/${encodeURIComponent(args.calendarId)}/events/${encodeURIComponent(args.eventId)}`, { sendUpdates: args.sendUpdates }, undefined, args.headers)
    }
  };
}
export function operationEventId(operationId, calendarId = '') { return createHash('sha256').update(`${calendarId}\n${operationId}`).digest('hex'); }

function toGoogleTime(value) { return 'date' in value ? { date: value.date } : { dateTime: value.dateTime, timeZone: value.timeZone }; }
function toGooglePatchTime(value) { return 'date' in value ? { date: value.date, dateTime: null, timeZone: null } : { date: null, dateTime: value.dateTime, timeZone: value.timeZone }; }
function toEventBody(input, eventId) {
  validateTiming(input.start, input.end, input.recurrence);
  const body = { id: eventId, summary: input.title, start: toGoogleTime(input.start), end: toGoogleTime(input.end) };
  if (input.description !== undefined) body.description = input.description;
  if (input.location !== undefined) body.location = input.location;
  if (input.recurrence) body.recurrence = input.recurrence;
  if (input.reminderMinutes) body.reminders = { useDefault: false, overrides: input.reminderMinutes.map(minutes => ({ method: 'popup', minutes })) };
  return body;
}
function fromGoogleEvent(event) { return { id: event.id, etag: event.etag, status: event.status, title: event.summary ?? '', description: event.description ?? '', location: event.location ?? '', start: event.start, end: event.end, ...(event.recurrence === undefined ? {} : { recurrence: event.recurrence }), recurringEventId: event.recurringEventId ?? null, eventType: event.eventType ?? 'default', reminders: event.reminders ?? { useDefault: true }, hasGuests: (event.attendees?.length ?? 0) > 0, htmlLink: event.htmlLink }; }

export async function listCalendars(api, { pageToken, limit }) {
  const { data } = await api.calendarList.list({ maxResults: limit, pageToken, showHidden: false });
  return { calendars: (data.items ?? []).map(c => ({ id: c.id, name: c.summaryOverride ?? c.summary ?? c.id, description: c.description ?? '', timeZone: c.timeZone, accessRole: c.accessRole, primary: c.primary === true, selected: c.selected === true })), nextPageToken: data.nextPageToken ?? null };
}
export async function listEvents(api, input) {
  const { data } = await api.events.list({ calendarId: input.calendarId, timeMin: input.timeMin, timeMax: input.timeMax, q: input.query, maxResults: input.limit, pageToken: input.pageToken, singleEvents: true, orderBy: 'startTime', showDeleted: false, eventTypes: input.eventTypes ?? ['default'] });
  return { events: (data.items ?? []).map(fromGoogleEvent), nextPageToken: data.nextPageToken ?? null, timeZone: data.timeZone };
}
export async function getEvent(api, input) { const { data } = await api.events.get({ calendarId: input.calendarId, eventId: input.eventId }); return fromGoogleEvent(data); }
export async function createEvent(api, input) {
  const eventId = operationEventId(input.operationId, input.calendarId);
  const body = toEventBody(input, eventId);
  const hash = createHash('sha256').update(JSON.stringify(body)).digest('hex');
  body.extendedProperties = { private: { sunlithOperationHash: hash } };
  try { const { data } = await api.events.insert({ calendarId: input.calendarId, sendUpdates: 'none', requestBody: body }); return { ...fromGoogleEvent(data), reused: false }; }
  catch (error) {
    const status = Number(error?.response?.status ?? error?.code);
    if (!Number.isFinite(status) || status === 409 || status === 429 || status >= 500) {
      let existing;
      try { existing = (await api.events.get({ calendarId: input.calendarId, eventId })).data; } catch {}
      if (existing) {
        if (existing.status === 'cancelled') throw new Error('This operation ID belongs to a deleted event. Use a new operation ID only if the user wants to create it again.');
        if (existing.extendedProperties?.private?.sunlithOperationHash !== hash) throw new Error('Operation ID already exists with different event details. Read the event before editing it.');
        return { ...fromGoogleEvent(existing), reused: true };
      }
    }
    throw error;
  }
}
export async function updateEvent(api, input) {
  const current = await getEvent(api, input);
  if (current.status === 'cancelled' || current.eventType !== 'default') throw new Error('Only active default calendar events can be updated by this companion.');
  if (current.hasGuests) throw new Error('Editing events with guests is not supported by this personal-calendar companion. Use your calendar app.');
  if (current.etag !== input.etag) throw new Error('Event changed since it was read. Read it again and use its latest etag.');
  const body = {};
  if (input.title !== undefined) body.summary = input.title;
  if (input.description !== undefined) body.description = input.description;
  if (input.location !== undefined) body.location = input.location;
  if (input.start) { validateTiming(input.start, input.end, input.recurrence ?? current.recurrence); body.start = toGooglePatchTime(input.start); body.end = toGooglePatchTime(input.end); }
  if (input.recurrence !== undefined) {
    if (current.recurringEventId) throw new Error('Recurrence rules can be edited on the whole-series master only.');
    validateTiming(input.start ?? current.start, input.end ?? current.end, input.recurrence);
    body.recurrence = input.recurrence;
  }
  if (input.reminderMinutes !== undefined) body.reminders = { useDefault: false, overrides: input.reminderMinutes.map(minutes => ({ method: 'popup', minutes })) };
  const { data } = await api.events.patch({ calendarId: input.calendarId, eventId: input.eventId, sendUpdates: 'none', requestBody: body, headers: { 'If-Match': input.etag } }); return fromGoogleEvent(data);
}
export async function deleteEvent(api, input) {
  let current;
  try { current = await getEvent(api, input); }
  catch (error) {
    const status = Number(error?.response?.status ?? error?.code);
    if (status === 404 || status === 410) return { deleted: true, alreadyDeleted: true, eventId: input.eventId };
    throw error;
  }
  if (current.status === 'cancelled') return { deleted: true, alreadyDeleted: true, eventId: input.eventId };
  if (current.eventType !== 'default') throw new Error('Only default calendar events can be deleted by this companion.');
  if (current.hasGuests) throw new Error('Deleting events with guests is not supported by this personal-calendar companion. Use your calendar app.');
  if (current.etag !== input.etag) throw new Error('Event changed since it was read. Read it again and use its latest etag.');
  try { await api.events.delete({ calendarId: input.calendarId, eventId: input.eventId, sendUpdates: 'none', headers: { 'If-Match': input.etag } }); return { deleted: true, eventId: input.eventId }; }
  catch (error) {
    const status = Number(error?.response?.status ?? error?.code);
    if (status === 404 || status === 410) return { deleted: true, alreadyDeleted: true, eventId: input.eventId };
    throw error;
  }
}
export function currentTime(timeZone) { const now = new Date(); return { timeZone, local: new Intl.DateTimeFormat('sv-SE', { dateStyle: 'full', timeStyle: 'long', hour12: false, timeZone }).format(now), utc: now.toISOString() }; }

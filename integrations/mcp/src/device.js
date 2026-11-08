import { request } from 'node:https';
import { Agent } from 'node:https';
import { connect } from 'node:tls';
import { createHash, timingSafeEqual } from 'node:crypto';
import { readFile, realpath, stat } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isIP } from 'node:net';
import { z } from 'zod';
import { deviceSchemas } from './device-validation.js';

export const REQUEST_LIMIT = 512 * 1024;
export const RESPONSE_LIMIT = 2 * 1024 * 1024;
export const TIMEOUT_MS = 10_000;
const repo = fileURLToPath(new URL('../../../', import.meta.url));
const configSchema = z.object({
  version: z.literal(1), baseUrl: z.string().max(100),
  certificateSha256: z.string().regex(/^[a-f0-9]{64}$/), token: z.string().regex(/^[A-Za-z0-9_-]{43}$/)
}).strict();
const messages = {
  400: 'Sunlith rejected the request. Check event times, recurrence and supported fields.',
  401: 'Sunlith pairing is no longer authorized. Pair the phone again.',
  403: 'This calendar is not allowed or is read-only. Check access on the phone.',
  404: 'The requested phone calendar or event was not found.',
  409: 'The event changed, the operation ID conflicts, or series confirmation is required. Read it again and confirm the whole series when applicable.',
  413: 'The request or result is too large. Use a smaller file or date range.',
  422: 'Sunlith cannot safely change this event. Check for guests, recurrence exceptions, or required whole-series confirmation.',
  429: 'Too many phone requests. Wait briefly before trying again.',
  500: 'The phone could not complete this request. Check Sunlith before trying again.',
  503: 'Sunlith is temporarily unavailable. Open the phone app and try again.'
};
const codeMessages = {
  INVALID_REQUEST: messages[400], BAD_REQUEST: messages[400], VALIDATION_ERROR: messages[400],
  UNAUTHORIZED: messages[401], FORBIDDEN: messages[403], READ_ONLY: messages[403], CALENDAR_NOT_ALLOWED: messages[403],
  NOT_FOUND: messages[404], CONFLICT: messages[409], STALE_ETAG: 'The event changed. Read its latest etag before trying again.',
  SERIES_SCOPE_REQUIRED: 'This is a repeating event. Confirm the whole series and use seriesScope:"series".',
  RECURRENCE_EXCEPTION: 'This series has exceptions. Sunlith cannot safely change it through this tool.',
  TOO_LARGE: messages[413], PAYLOAD_TOO_LARGE: messages[413], UNAVAILABLE: messages[503],
  bad_request: messages[400], unauthorized: messages[401], calendar_not_allowed: messages[403], not_found: messages[404],
  revision_mismatch: 'The event changed. Read its latest etag before trying again.',
  operation_conflict: 'This operationId was already used with different details. Reuse the original details or choose a new operationId for a new event.',
  blocked: messages[422], rate_limited: messages[429], too_large: messages[413],
  permission_missing: 'Calendar access is missing on the phone. Allow calendar access in Sunlith and try again.', internal: messages[500]
};
const blockedReasons = {
  school_managed: 'This event is updated by the school portal. Disconnect the school schedule on the phone before editing it.',
  series_scope_required: codeMessages.SERIES_SCOPE_REQUIRED,
  recurrence_exception: codeMessages.RECURRENCE_EXCEPTION,
  guests: 'This event has guests. Sunlith cannot safely change meetings with invitations through this tool.',
  read_only: messages[403], invalid_provider_data: 'This event has invalid stored calendar data. Review it on the phone before editing.'
};

function outsideRepository(path, root) {
  const part = relative(root, path);
  return part !== '' && (part === '..' || part.startsWith(`..${sep}`) || isAbsolute(part));
}
export function validateDeviceUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('Device URL must be HTTPS with a private IPv4 address.'); }
  const host = url.hostname;
  const octets = host.split('.').map(Number);
  const privateIp = isIP(host) === 4 && (octets[0] === 10 || octets[0] === 127 ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168) ||
    (octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127));
  if (value !== value.trim() || url.protocol !== 'https:' || !privateIp || url.username || url.password || url.pathname !== '/' || url.search || url.hash ||
      !/^https:\/\/\d+\.\d+\.\d+\.\d+(?::[1-9][0-9]{0,4})?\/?$/.test(value) ||
      value.match(/^https:\/\/([^:/]+)/)?.[1] !== host || Number(url.port || 443) > 65535) {
    throw new Error('Device URL must be HTTPS with a private literal IPv4 address, optional port, and no credentials, path, query or fragment.');
  }
  return url;
}
async function privateFile(path, maximum, label) {
  if (typeof path !== 'string' || !isAbsolute(path) || !outsideRepository(resolve(path), repo)) throw new Error(`${label} must be an absolute path outside the repository.`);
  try {
    const [actual, actualRepo] = await Promise.all([realpath(path), realpath(repo)]);
    if (!outsideRepository(actual, actualRepo)) throw new Error('private-path');
    if (label === 'Calendar file' && extname(actual).toLowerCase() !== '.ics') throw new Error('private-extension');
    const info = await stat(actual);
    if (!info.isFile() || info.size > maximum || (label === 'Device configuration' && process.platform !== 'win32' && (info.mode & 0o077) !== 0)) throw new Error('private-file');
    const bytes = await readFile(actual);
    if (bytes.length > maximum) throw new Error('private-limit');
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch { throw new Error(`Cannot read ${label.toLowerCase()}. Use a private UTF-8 file outside the repository within the size limit.`); }
}
export async function loadDeviceConfig(path) {
  const source = await privateFile(path, 4096, 'Device configuration');
  let parsed;
  try { parsed = configSchema.parse(JSON.parse(source)); }
  catch { throw new Error('Invalid device configuration. Pair the phone again.'); }
  validateDeviceUrl(parsed.baseUrl);
  return parsed;
}

export async function callDevice(config, tool, input = {}, { timeoutMs } = {}) {
  const checked = configSchema.safeParse(config);
  if (!checked.success) throw new Error('Invalid device configuration. Pair the phone again.');
  const url = validateDeviceUrl(config.baseUrl);
  const schema = Object.hasOwn(deviceSchemas, tool) ? deviceSchemas[tool] : null;
  if (!schema) throw new Error('Unknown Sunlith device tool.');
  const parsed = schema.safeParse(input);
  if (!parsed.success) throw new Error('Invalid Sunlith device tool arguments.');
  let args = parsed.data;
  if (tool === 'import_calendar_file') {
    if (extname(args.filePath).toLowerCase() !== '.ics') throw new Error('Choose an ICS file.');
    args = { calendarId: args.calendarId, ics: await privateFile(args.filePath, 256 * 1024, 'Calendar file'), confirm: true };
    tool = 'import_calendar';
  }
  timeoutMs ??= tool === 'import_calendar' ? 180_000 : TIMEOUT_MS;
  const body = Buffer.from(JSON.stringify({ tool, arguments: args }), 'utf8');
  if (body.length > REQUEST_LIMIT) throw new Error(messages[413]);
  const expectedPin = Buffer.from(config.certificateSha256, 'hex');
  const agent = new Agent({ keepAlive: false, maxSockets: 1 });
  let connectingSocket;
  agent.createConnection = (options, callback) => {
    const socket = connect({ host: url.hostname, port: Number(url.port || 443), rejectUnauthorized: false, minVersion: 'TLSv1.2' });
    connectingSocket = socket;
    let completed = false;
    const finish = (error) => {
      if (completed) return;
      completed = true;
      callback(error, error ? undefined : socket);
    };
    socket.once('error', () => finish(new Error('Cannot connect securely to the paired Sunlith phone.')));
    socket.once('secureConnect', () => {
      const certificate = socket.getPeerCertificate();
      const pin = certificate.raw ? createHash('sha256').update(certificate.raw).digest() : Buffer.alloc(0);
      if (pin.length !== expectedPin.length || !timingSafeEqual(pin, expectedPin)) {
        finish(new Error('Phone certificate does not match the pairing. No calendar request was sent.'));
        socket.destroy();
        return;
      }
      finish(null);
    });
  };
  return new Promise((resolveResult, reject) => {
    let settled = false;
    let req;
    const finish = (error, result) => {
      if (settled) return;
      settled = true;
      clearTimeout(deadline);
      req?.destroy();
      connectingSocket?.destroy();
      agent.destroy();
      if (error) reject(error); else resolveResult(result);
    };
    const deadline = setTimeout(() => finish(new Error('Sunlith phone request timed out. Open the app and check the private connection.')), timeoutMs);
    try {
      req = request({ hostname: url.hostname, port: Number(url.port || 443), path: '/v1/tools', method: 'POST', agent,
        headers: { authorization: `Bearer ${config.token}`, 'content-type': 'application/json; charset=utf-8', 'content-length': body.length, accept: 'application/json' }, maxHeaderSize: 16 * 1024 }, response => {
        let size = 0;
        const chunks = [];
        if (Number(response.headers['content-length']) > RESPONSE_LIMIT) { finish(new Error(messages[413])); return; }
        response.on('data', chunk => {
          size += chunk.length;
          if (size > RESPONSE_LIMIT) { finish(new Error(messages[413])); return; }
          chunks.push(chunk);
        });
        response.once('error', () => finish(new Error('Sunlith phone response was interrupted.')));
        response.once('end', () => {
          if (settled) return;
          let payload;
          try { payload = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); }
          catch { finish(new Error(messages[response.statusCode] ?? 'Sunlith returned an invalid response.')); return; }
          if (response.statusCode !== 200) {
            const knownCode = typeof payload?.error?.code === 'string' && Object.hasOwn(codeMessages, payload.error.code) ? payload.error.code : null;
            const reason = payload?.error?.reason;
            const blockedMessage = knownCode && typeof reason === 'string' && Object.hasOwn(blockedReasons, reason) ? blockedReasons[reason] : null;
            finish(new Error(blockedMessage ?? (knownCode ? codeMessages[knownCode] : messages[response.statusCode] ?? 'Sunlith refused the request. Redirects are not followed.')));
            return;
          }
          if (!payload || typeof payload !== 'object' || Array.isArray(payload) || !Object.hasOwn(payload, 'result') || Object.hasOwn(payload, 'error') ||
              !payload.result || typeof payload.result !== 'object' || Array.isArray(payload.result)) {
            finish(new Error('Sunlith returned an invalid response.')); return;
          }
          finish(null, payload.result);
        });
      });
      req.once('error', error => finish(new Error(error.message.startsWith('Phone certificate does not match') ? error.message : 'Cannot connect securely to the paired Sunlith phone.')));
      req.end(body);
    } catch { finish(new Error('Cannot connect securely to the paired Sunlith phone.')); }
  });
}

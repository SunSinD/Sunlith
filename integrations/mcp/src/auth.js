import { randomBytes, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir, chmod, rename } from 'node:fs/promises';
import { dirname, join, relative, resolve, isAbsolute, sep } from 'node:path';
import { homedir } from 'node:os';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { OAuth2Client } from 'google-auth-library';

export const SCOPES = [
  'https://www.googleapis.com/auth/calendar.events',
  'https://www.googleapis.com/auth/calendar.calendarlist.readonly'
];
const DEFAULT_CONFIG = join(homedir(), '.config', 'sunlith');
export const paths = {
  credentials: process.env.SUNLITH_GOOGLE_CREDENTIALS,
  token: process.env.SUNLITH_GOOGLE_TOKEN ?? join(DEFAULT_CONFIG, 'google-token.json')
};
export function validateTokenPath(value) {
  if (!isAbsolute(value)) throw new Error('SUNLITH_GOOGLE_TOKEN must be an absolute path outside the repository.');
  const repo = fileURLToPath(new URL('../../../', import.meta.url));
  const part = relative(repo, resolve(value));
  if (part === '' || (!part.startsWith(`..${sep}`) && part !== '..' && !isAbsolute(part))) throw new Error('Google tokens must be stored outside the Sunlith repository.');
}

async function readCredentials() {
  const file = paths.credentials;
  if (!file) throw new Error('Set SUNLITH_GOOGLE_CREDENTIALS to your Google OAuth Desktop credentials JSON path.');
  const raw = JSON.parse(await readFile(file, 'utf8'));
  const value = raw.installed;
  if (!value?.client_id || !value?.client_secret) throw new Error('Credentials must be a Google OAuth Desktop client JSON file (installed).');
  return value;
}

export async function loadClient() {
  validateTokenPath(paths.token);
  const credentials = await readCredentials();
  const client = new OAuth2Client(credentials.client_id, credentials.client_secret);
  try {
    const tokens = JSON.parse(await readFile(paths.token, 'utf8'));
    if (!tokens || typeof tokens !== 'object' || typeof tokens.refresh_token !== 'string' || !tokens.refresh_token) throw new Error('token file has no refresh_token');
    if (tokens.access_token !== undefined && typeof tokens.access_token !== 'string') throw new Error('token file has an invalid access_token');
    if (tokens.expiry_date !== undefined && (!Number.isFinite(tokens.expiry_date) || tokens.expiry_date <= 0)) throw new Error('token file has an invalid expiry_date');
    client.setCredentials(tokens);
  }
  catch (error) { if (error.code !== 'ENOENT') throw new Error(`Cannot read Sunlith token file: ${error.message}`); }
  if (!client.credentials.refresh_token) throw new Error('Google account is not connected or token has no refresh_token. Run npm run auth again.');
  let save = Promise.resolve();
  client.on('tokens', tokens => {
    save = save.then(() => saveTokens({ ...client.credentials, ...tokens })).catch(() => console.error('Could not save refreshed Google tokens. Check the Sunlith config folder permissions.'));
  });
  return client;
}

export async function saveTokens(tokens) {
  validateTokenPath(paths.token);
  await mkdir(dirname(paths.token), { recursive: true, mode: 0o700 });
  const temporary = `${paths.token}.${randomBytes(6).toString('hex')}.tmp`;
  await writeFile(temporary, `${JSON.stringify(tokens, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
  await rename(temporary, paths.token);
  if (process.platform !== 'win32') await chmod(paths.token, 0o600);
}

export function parseLoopbackUrl(value, expectedState) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password || url.pathname !== '/oauth2callback') throw new Error('OAuth callback must use the expected HTTP 127.0.0.1 loopback path.');
  if (url.searchParams.getAll('state').length !== 1 || url.searchParams.getAll('code').length > 1) throw new Error('OAuth callback contains duplicate or missing parameters.');
  const actual = Buffer.from(url.searchParams.get('state') ?? '');
  const expected = Buffer.from(expectedState);
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw new Error('OAuth state did not match; refusing the callback.');
  const error = url.searchParams.get('error');
  if (error) throw new Error(`Google OAuth failed: ${error}`);
  const code = url.searchParams.get('code');
  if (!code) throw new Error('OAuth callback did not contain a code.');
  return code;
}

export async function authenticate() {
  validateTokenPath(paths.token);
  const credentials = await readCredentials();
  const server = createServer();
  await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', resolve).once('error', reject));
  const address = server.address();
  const redirectUri = `http://127.0.0.1:${address.port}/oauth2callback`;
  const client = new OAuth2Client(credentials.client_id, credentials.client_secret, redirectUri);
  const { codeVerifier, codeChallenge } = await client.generateCodeVerifierAsync();
  const state = randomBytes(32).toString('base64url');
  const authUrl = client.generateAuthUrl({ access_type: 'offline', prompt: 'consent', scope: SCOPES, state, code_challenge_method: 'S256', code_challenge: codeChallenge });
  console.error(`Open this URL in a browser to authorize Sunlith:\n${authUrl}`);
  const code = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('OAuth callback timed out after 5 minutes.')), 300000);
    server.on('request', (req, res) => {
      res.setHeader('Content-Type', 'text/plain; charset=utf-8');
      res.setHeader('Cache-Control', 'no-store');
      res.setHeader('Content-Security-Policy', "default-src 'none'");
      if (req.method !== 'GET' || !req.url?.startsWith('/oauth2callback?')) { res.statusCode = 404; res.end('Not found'); return; }
      try {
        const callback = new URL(req.url, redirectUri);
        const value = parseLoopbackUrl(callback.href, state);
        clearTimeout(timer); res.end('Authorization received. You may close this tab while Sunlith validates it.'); resolve(value);
      } catch (error) { clearTimeout(timer); res.statusCode = 400; res.end(error.message); reject(error); }
    });
  }).finally(() => server.close());
  const { tokens } = await client.getToken({ code, codeVerifier });
  if (!tokens.refresh_token) throw new Error('Google did not return a refresh token. Re-run authorization with consent, then try again.');
  await saveTokens(tokens);
  console.error(`Saved Sunlith Google tokens to ${paths.token}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) authenticate().catch(error => { console.error(error.message); process.exitCode = 1; });

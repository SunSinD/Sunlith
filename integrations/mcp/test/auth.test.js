import test from 'node:test';
import assert from 'node:assert/strict';
import { parseLoopbackUrl, validateTokenPath } from '../src/auth.js';
import { loadClient, paths } from '../src/auth.js';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { join } from 'node:path';
test('loopback callback requires matching state and code', () => {
  assert.equal(parseLoopbackUrl('http://127.0.0.1:4312/oauth2callback?code=abc&state=xyz', 'xyz'), 'abc');
  assert.throws(() => parseLoopbackUrl('https://evil.example/?code=abc&state=xyz', 'xyz'));
  assert.throws(() => parseLoopbackUrl('http://127.0.0.1:4312/?code=abc&state=nope', 'xyz'));
  assert.throws(() => parseLoopbackUrl('http://127.0.0.1:4312/oauth2callback?code=abc&state=xyz&state=xyz', 'xyz'));
  assert.throws(() => parseLoopbackUrl('http://127.0.0.1:4312/oauth2callback?error=access_denied&state=xyz', 'xyz'));
});
test('tokens cannot accidentally be stored in the repository', () => {
  assert.throws(() => validateTokenPath('relative-token.json'), /absolute path/);
  assert.throws(() => validateTokenPath(fileURLToPath(new URL('../token.json', import.meta.url))), /outside/);
  assert.doesNotThrow(() => validateTokenPath(join(homedir(), '.config', 'sunlith', 'google-token.json')));
});
test('loaded credentials require a refresh token and valid token JSON', async () => {
  const directory = await mkdtemp(join(process.env.TEMP ?? process.env.TMPDIR ?? homedir(), 'sunlith-auth-'));
  const oldCredentials = paths.credentials;
  const oldToken = paths.token;
  try {
    paths.credentials = join(directory, 'credentials.json');
    paths.token = join(directory, 'token.json');
    await writeFile(paths.credentials, JSON.stringify({ installed: { client_id: 'client', client_secret: 'secret' } }));
    await writeFile(paths.token, JSON.stringify({ access_token: 'only-access-token' }));
    await assert.rejects(loadClient(), /refresh_token/);
    await writeFile(paths.token, JSON.stringify({ refresh_token: 'refresh', access_token: 'access', expiry_date: 1234567890000 }));
    const client = await loadClient();
    assert.equal(client.credentials.refresh_token, 'refresh');
  } finally {
    paths.credentials = oldCredentials;
    paths.token = oldToken;
    await rm(directory, { recursive: true, force: true });
  }
});

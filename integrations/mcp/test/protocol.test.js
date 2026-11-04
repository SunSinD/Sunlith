import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('real stdio handshake lists strict tools and serves time without credentials', async () => {
  const transport = new StdioClientTransport({ command: process.execPath, args: [fileURLToPath(new URL('../src/index.js', import.meta.url))], stderr: 'pipe' });
  const client = new Client({ name: 'sunlith-test', version: '1.0.0' });
  try {
    await client.connect(transport);
    const result = await client.listTools();
    assert.equal(result.tools.length, 7);
    const create = result.tools.find(tool => tool.name === 'create_event');
    assert.equal(create.inputSchema.additionalProperties, false);
    assert.equal(create.inputSchema.required.includes('operationId'), true);
    const time = await client.callTool({ name: 'get_current_time', arguments: { timeZone: 'America/Toronto' } });
    assert.equal(time.isError, undefined);
    assert.equal(time.structuredContent.timeZone, 'America/Toronto');
    const invalid = await client.callTool({ name: 'delete_event', arguments: { calendarId: 'primary', eventId: 'abc', etag: '"1"' } });
    assert.equal(invalid.isError, true);
  } finally { await client.close(); }
});
test('auth entry point actually executes and reports missing credentials', () => {
  const env = { ...process.env }; delete env.SUNLITH_GOOGLE_CREDENTIALS;
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('../src/auth.js', import.meta.url))], { env, encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, '');
  assert.match(result.stderr, /SUNLITH_GOOGLE_CREDENTIALS/);
});

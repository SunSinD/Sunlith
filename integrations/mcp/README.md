# Sunlith MCP companion

The local MCP server that lets your AI tools control personal Google Calendar events. Sunlith on Android uses the device's shared calendar storage; this companion connects your AI tools to the same Google calendar from your computer.

There is no AI chat or server inside the APK. The MCP companion uses standard input/output. Only the five-minute Google authorization flow opens a temporary listener, bound to `127.0.0.1`.

Requires Node.js 22 or later. Install with `npm ci`, connect an account with `npm run auth`, and use `npm start` from an MCP client. Authentication prints a Google sign-in URL; open it in a browser on the same computer.

See [setup and client configurations](../../docs/AI-INTEGRATION.md) and [research and limitations](../../docs/RESEARCH-AI.md).

```powershell
npm ci
npm test
npm run check
```

The seven tools are `list_calendars`, `list_events`, `get_event`, `create_event`, `update_event`, `delete_event`, and `get_current_time`. Calendar/event IDs returned here are Google IDs, not Android database row numbers.

This is an alpha integration. Tests use mock Calendar API methods plus a real local MCP handshake. Real Google authorization and phone sync require your account and device, and have not been verified by those tests.

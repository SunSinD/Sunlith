# Sunlith MCP companion

Manage calendars on your Sunlith phone from your computer over WiFi or Tailscale. The direct connection needs no Google account or second calendar app.

The companion uses standard input/output. Sunlith runs a private HTTPS server while Computer connection is on. Pairing checks the phone certificate and grants access to calendars you select. Stop pauses access. Unpair revokes it.

Requires Node.js 22 or later. [Download and extract the companion ZIP](https://github.com/SunSinD/Sunlith/releases/latest), or clone the repository and open `integrations/mcp`. Run `npm ci`. In Sunlith, open Settings > Computer connection, choose calendars and tap Connect computer to share the connection file privately to your computer. With Tailscale, choose Tailscale and then your computer in the share list. Keep it outside the repository. Start the phone connection, then configure your assistant to launch `node` with the full path to `src/index.js` and `SUNLITH_DEVICE_CONFIG` pointing to that file. No browser login is needed.

Keep both devices on the same WiFi or private Tailscale network. The phone connection must remain on. Android battery restrictions can interrupt background access. It does not restart automatically after reboot.

See [setup and client configurations](../../docs/AI-INTEGRATION.md) and [research and limitations](../../docs/RESEARCH-AI.md).

```powershell
npm ci
npm test
npm run check
```

Both modes provide `list_calendars`, `list_events`, `get_event`, `create_event`, `update_event`, `delete_event` and `get_current_time`. The direct connection also provides `import_calendar_file` for imports you request from a private ICS file. It uses Android event IDs. Google uses its own IDs. IDs cannot be exchanged between modes.

The direct connection uses the phone clock and time zone for `get_current_time`. Read an event's current `etag` before updating or deleting it. Repeating events require `seriesScope: "series"`. Editing one occurrence or a series with exceptions is blocked.

Google mode remains a separate option: set `SUNLITH_GOOGLE_CREDENTIALS`, run `npm run auth`, and sync that account using an Android account adapter. Direct mode never loads Google authentication, even when its pairing configuration is invalid.

Tests cover the stdio MCP protocol, real local HTTPS, certificate mismatch before sending credentials, input validation, and mocked Google API calls. See the setup guide for device verification and limits.

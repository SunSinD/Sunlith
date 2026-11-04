# Connect your assistant

Sunlith is a clean Android calendar with widgets and reminders. On your phone, an assistant can hand over a calendar file or event draft for you to approve. For automatic computer control, connect the optional [MCP companion](../integrations/mcp): your assistant changes your Google calendar, then Android account sync brings those changes into Sunlith and its widget. You can also use Sunlith without connecting an assistant.

The phone app does not contain an AI model, collect AI API keys, or keep a server running.

## On your phone without a computer

No Google account is needed for this workflow. Use a writable calendar already on your phone, or create an on-device calendar in Settings > Calendar.

1. Ask a mobile assistant that can generate downloadable files to create a `.ics` file for your event. Give the date, start/end time, timezone, and reminder you want. Check those details before importing.
2. Download the file and open or share it with Sunlith. If your file app does not offer Sunlith, use Settings > Import and export > Import .ics file instead.
3. Review the preview, select the destination calendar, and tap Import. Check the saved event and reminder.

Opening or sharing a supported calendar attachment starts the import preview. It does not save events automatically. Direct file handoff supports `text/calendar`, `text/x-vcalendar`, and `application/ics` attachments provided through Android's content-file access. Generic file types can use the in-app picker instead. The assistant must be able to produce a file; Sunlith does not interpret chat messages or plain text as event commands.

Assistant or voice apps that support Android's standard `ACTION_INSERT` calendar intent can open a personal event draft in Sunlith. Review the calendar, time, and reminders, then tap Save. This handoff requires explicit valid start and end times; recurrence and guest extras are rejected. It does not support editing or deleting existing events. Whether an assistant can launch this intent depends on that assistant, and support has not been verified for every mobile app.

Neither handoff gives a computer assistant access to read or edit your phone-only or Samsung-account calendar. Automatic remote control of those calendars would need a separate authenticated bridge, which is not implemented.

## Automatic control from your computer

The following setup uses Google Calendar. Your computer is needed while the local assistant performs a task. It does not need to stay on afterward for Android to show synced events or reminders.

## Before connecting

1. Add your Google account in Android's account settings and enable Calendar sync.
2. Give Sunlith calendar permission and select the Google calendar you want to display. Select that same calendar when saving events you want to control from your computer.
3. On your computer, install Node.js 22 or later and [download the companion ZIP](https://github.com/SunSinD/Sunlith/releases/latest). Extract it and read `START-HERE.md`. You can also clone the repository and use `integrations/mcp`.
4. In Google Cloud Console, create a project, enable **Google Calendar API**, configure the OAuth consent screen, and create an OAuth client of type **Desktop app**. If the app is in Testing, add your own Google account as a test user.
5. Download the client JSON into your private user profile, outside the repository. Example: `C:\Users\you\.config\sunlith\google-desktop.json`.

The companion requests only `calendar.events` and `calendar.calendarlist.readonly`. It cannot change sharing permissions or create/delete calendars. The event scope permits viewing and editing events on accessible calendars, subject to each calendar's permissions. It is not limited to your selected calendar; read-only calendars remain read-only.

Google OAuth apps in external **Testing** mode usually need authorization again after seven days. Moving your OAuth app to Production changes that testing restriction, but public apps using sensitive scopes can require Google verification. This project does not publish a shared production OAuth client or perform that verification for you.

## Install and authorize

Open PowerShell in the extracted companion folder containing `package.json`, or in `integrations/mcp` if you cloned the repository. Run:

```powershell
npm ci
$env:SUNLITH_GOOGLE_CREDENTIALS = 'C:\Users\you\.config\sunlith\google-desktop.json'
npm run auth
```

Open the printed Google sign-in URL in a browser on this computer. The temporary callback server listens only on `127.0.0.1`, checks a random state value and PKCE, and closes after the result or a five-minute timeout.

By default, tokens are saved to `~/.config/sunlith/google-token.json`. `SUNLITH_GOOGLE_TOKEN` can choose a different private path. Keep that file outside the repository. On Unix, new token files use mode `0600`. On Windows, Node's mode bits do not enforce a private Windows ACL: use your private user-profile folder and its normal Windows account permissions. Tokens are local files, not encrypted vault entries. Revoke access in your Google account and delete this file to disconnect.

Replace the credentials path with the private JSON path you chose. Your assistant starts `node` directly; do not use an interactive sign-in command as the MCP startup command. In the configurations below, replace `D:/Projects/Sunlith/integrations/mcp/src/index.js` with the full path to `src/index.js` in your extracted or cloned companion folder. Start a new client conversation after configuring the server.

## Provider coverage

All eight providers below have a documented local MCP interface. These are host-side setup instructions, not claims of live account testing. Use the client version whose current documentation matches the format. Gemini CLI is included as another compatible client.

| Assistant client | Where to configure Sunlith on the computer | Check connection |
| --- | --- | --- |
| Codex | `codex mcp add` or `~/.codex/config.toml` | `codex mcp list` |
| Claude | `claude mcp add` | `claude mcp list` |
| Cursor | `~/.cursor/mcp.json` | MCP settings and a new agent conversation |
| Grok | `~/.grok/config.toml` | `grok mcp doctor sunlith` |
| OpenCode | `~/.config/opencode/opencode.json` | `opencode mcp list` |
| Antigravity | `~/.gemini/config/mcp_config.json` for its CLI/IDE | `/mcp`; other runtimes require separate verification |
| Muse Code | `~/.config/muse/settings.json` | `/mcp` |
| Pi | `~/.pi/agent/mcp.json` | `pi mcp list` |

These clients launch the same local Sunlith server. There is no AI subscription or provider account inside the APK. A client without local stdio MCP support needs another adapter; this beta does not supply an HTTP gateway.

## Claude Code

```powershell
claude mcp add --scope user --env "SUNLITH_GOOGLE_CREDENTIALS=C:/Users/you/.config/sunlith/google-desktop.json" --transport stdio sunlith -- node "D:/Projects/Sunlith/integrations/mcp/src/index.js"
claude mcp list
```

Claude's `--env` option accepts multiple values. Keep `--transport stdio` between the last environment value and the server name, as shown, so the name is not consumed as another variable. See [Claude's MCP documentation](https://code.claude.com/docs/en/mcp).

## Codex

```powershell
codex mcp add sunlith --env "SUNLITH_GOOGLE_CREDENTIALS=C:/Users/you/.config/sunlith/google-desktop.json" -- node "D:/Projects/Sunlith/integrations/mcp/src/index.js"
codex mcp list
```

The equivalent `~/.codex/config.toml` entry is:

```toml
[mcp_servers.sunlith]
command = "node"
args = ["D:/Projects/Sunlith/integrations/mcp/src/index.js"]

[mcp_servers.sunlith.env]
SUNLITH_GOOGLE_CREDENTIALS = "C:/Users/you/.config/sunlith/google-desktop.json"
```

## Gemini CLI and Cursor

Merge this entry into Gemini CLI's `~/.gemini/settings.json` or Cursor's `~/.cursor/mcp.json`. Keep any existing settings and servers:

```json
{
  "mcpServers": {
    "sunlith": {
      "command": "node",
      "args": ["D:/Projects/Sunlith/integrations/mcp/src/index.js"],
      "env": {
        "SUNLITH_GOOGLE_CREDENTIALS": "C:/Users/you/.config/sunlith/google-desktop.json"
      }
    }
  }
}
```

If `node` is not available to your desktop client's environment, replace it with the full path to `node.exe`. macOS/Linux use their native paths. These configurations follow each client's documented MCP format. End-to-end use in each client has not been tested here; the companion itself is tested with the official MCP SDK client over stdio.

## Grok

Merge this into `~/.grok/config.toml`:

```toml
[mcp_servers.sunlith]
command = "node"
args = ["D:/Projects/Sunlith/integrations/mcp/src/index.js"]
env = { SUNLITH_GOOGLE_CREDENTIALS = "C:/Users/you/.config/sunlith/google-desktop.json" }
```

Run `grok mcp list`, then `grok mcp doctor sunlith`. In the terminal UI, `/mcps` opens the server list and `r` reloads changes. [Official Grok MCP documentation](https://docs.x.ai/build/features/mcp-servers) also explains configuration imported from other clients; an explicit Grok entry makes the source clear.

## OpenCode

Merge this entry into `~/.config/opencode/opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "sunlith": {
      "type": "local",
      "command": ["node", "D:/Projects/Sunlith/integrations/mcp/src/index.js"],
      "environment": {
        "SUNLITH_GOOGLE_CREDENTIALS": "C:/Users/you/.config/sunlith/google-desktop.json"
      },
      "enabled": true
    }
  }
}
```

Check with `opencode mcp list`, then restart the provider/session after configuration changes. OpenCode uses `environment` and a command array, unlike the `env`/`args` JSON used by Cursor. See [OpenCode MCP servers](https://opencode.ai/docs/mcp-servers/) and [configuration](https://opencode.ai/docs/config/).

## Antigravity

For the Antigravity CLI or IDE, put the same `mcpServers` JSON shown under [Gemini CLI and Cursor](#gemini-cli-and-cursor) in `~/.gemini/config/mcp_config.json`. The workspace alternative is `.agents/mcp_config.json`. Open `/mcp` in the CLI to inspect and reload connections; in the IDE use **MCP Servers > Manage MCP Servers > View raw config**. See [Antigravity MCP documentation](https://antigravity.google/docs/mcp/).

Apps that host Antigravity through the Agent Client Protocol (ACP) may use a separate runtime, sign-in and MCP configuration. Do not assume that they inherit the CLI/IDE settings. Start a fresh session and check whether all seven Sunlith tools appear before relying on it. Direct CLI/IDE configuration is documented; loading through other runtimes remains unverified. Gemini CLI's `settings.json` is a different configuration file.

## Muse Code

Merge into `~/.config/muse/settings.json`, keeping all existing settings:

```json
{
  "schema_version": 1,
  "mcp_servers": {
    "sunlith": {
      "transport": "stdio",
      "command": "node",
      "args": ["D:/Projects/Sunlith/integrations/mcp/src/index.js"],
      "env": {
        "SUNLITH_GOOGLE_CREDENTIALS": "C:/Users/you/.config/sunlith/google-desktop.json"
      },
      "enabled": true,
      "mode": "optional"
    }
  }
}
```

Muse requires `schema_version: 1` in an existing settings file. `mode: "optional"` lets the agent continue if the calendar server cannot start; Sunlith tools will be unavailable until the problem is fixed. Install Muse and run `muse login` on the computer where it runs, then restart Muse and open `/mcp` to check the inventory. See [Muse extension/MCP documentation](https://dev.meta.ai/docs/muse-code/extending) and [configuration](https://dev.meta.ai/docs/muse-code/configuration).

## Pi

Current Pi includes native stdio MCP support. Merge the `mcpServers` JSON from [Gemini CLI and Cursor](#gemini-cli-and-cursor) into `~/.pi/agent/mcp.json`. Add `"exposure": "direct"` alongside `command` if you want its seven tools declared directly to the model rather than found through tool search/code mode.

Run `pi mcp list` to check all connections. Inside Pi, use `/mcp` to inspect tools and `/reload` after changing files. Project configuration in `.pi/mcp.json` needs project trust. If `PI_CODING_AGENT_DIR` selects another user directory, configure that directory rather than an unrelated default profile. Older Pi releases may need upgrading; do not assume this native configuration applies to them. See [Pi's MCP guide](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/mcp.md).

## Other hosts and remote access

If another app runs one of these assistants for you, configure Sunlith on the computer and user account where that assistant runs. A mobile remote client can send prompts to that session when the computer is reachable. It does not need calendar credentials inside Sunlith's APK.

Hosts can use separate account/config directories. For example, `CODEX_HOME`, `CLAUDE_CONFIG_DIR` or `PI_CODING_AGENT_DIR` may point at another profile. Start a fresh session and check its Sunlith tool list after setup. The APK links to these guides; it does not install CLIs or automatically configure the computer. No client has been tested here with a real Google account.

## First prompt and event safety

```text
Use Sunlith's get_current_time and list_calendars tools. Show my writable calendars and ask which one to use. Then add a dentist appointment tomorrow from 5 p.m. to 6 p.m. in America/Toronto, with a reminder 30 minutes before it starts. Return the saved date, time, calendar name, and event link.
```

- Use a calendar ID from `list_calendars`. Android's numeric calendar/event row IDs are local to the phone and cannot be used with this server.
- Call `get_current_time` before interpreting "today", "tomorrow" or a weekday. It defaults to the computer's IANA timezone, falling back to UTC if unavailable. Its `localIso` includes the numeric UTC offset and its `weekday` is in English. Pass an explicit timezone if you are travelling or scheduling in another zone.
- `create_event` requires an `operationId`, a unique string chosen once for this request. Retry with the same ID and exactly the same details after network failures. This avoids duplicate events. Changed details need an explicit edit or a genuinely new operation.
- Timed events require an RFC3339 date-time with offset and an IANA timezone. For Toronto, daylight saving changes the offset during the year; use the offset for the event's date. The server rejects nonexistent local times and mismatched zone offsets, and accepts either valid offset during a repeated fall-back hour.
- All-day events use dates. The end is exclusive: one day on November 5 uses start `2026-11-05` and end `2026-11-06`.
- Read an event before updating/deleting it and pass the returned `etag`. If it has changed, read it again. Deletion also requires `confirm: true` after the user requests cancellation of that exact event.
- The companion handles personal events. It does not add guests or send invitations, and rejects edits/deletes of events that already have guests. Use your calendar app for those events.
- Recurrence accepts a single basic `RRULE` with daily, weekly, monthly or yearly frequency. `list_events` expands series into instances and returns each instance's `recurringEventId`. For one occurrence, read/edit/delete its exact instance ID; for the whole series, call `get_event` using `recurringEventId`, then use the master ID and its etag. Recurrence-rule changes require that master and are refused when its stored rules include additional rules or exception dates. There is no separate `instances` tool or “this and following” series split.
- New events use only the requested popup reminders. Omitting `reminderMinutes` or sending an empty list creates no reminders, including calendar email defaults. On update, omitting it preserves existing reminders; an empty list removes them.
- `list_events` defaults to ordinary (`default`) events. Its `eventTypes` option can read the listed Google special types, but mutation tools only support active ordinary events.
- Event query bounds mean `event.end > timeMin` and `event.start < timeMax`; events touching a boundary without overlapping are excluded. Query bounds may use UTC independently of an event's own timezone.
- List tools return at most 100 items per page and expose `nextPageToken`. Event queries are limited to 366 days per request. Request the next page if necessary; the first page is not a complete archive.

## Shared and local calendars

Events already stored in your Google account appear through the same synced calendar. Connecting an assistant does not copy them into another calendar.

Samsung Calendar is an app that can display several kinds of calendar. An event shown there might belong to a Google account, another account, or a phone-only calendar. Check the calendar/account name on the event; using Samsung Calendar does not by itself make the event available through Google's API.

Sunlith can use phone-only calendars without a Google account. The computer companion cannot currently read or edit those calendars, or a Samsung account calendar. Direct automatic updates would require a separate authenticated bridge, which is not implemented. Merely selecting a local calendar in Sunlith does not upload it to Google.

If you choose to move phone-only events into a synced calendar for computer control, save an ICS backup first, import into the chosen destination, and verify dates, recurrence and reminders. Keep the original backup. Moving your events is optional.

Sunlith's Android calendar provider can also show accounts supplied by other sync adapters. For an open-source backend, Nextcloud Calendar with DAVx⁵ is an option. This companion's current adapter is Google-only; CalDAV, Outlook/Microsoft Graph and direct phone-local control are not implemented.

## Verification status

Automated checks cover strict argument validation, bounded list queries, Google-safe IDs, repeat creation after network ambiguity, changed-payload conflicts, partial update preservation and explicit field clearing for timed/all-day conversion, timezone/DST checks, event-type filters, cancelled-event deletion, etag guards, guest-event protection, OAuth callback/token validation, a real local MCP initialization/tool call, and startup without credentials. They do not replace a real Google sign-in, account API test, Android sync check, or reminder delivery test on your phone.

# Assistant integration research

Checked November 4, 2026. Sources below were fetched and read, not inferred from search snippets. Documentation describes supported interfaces; it does not establish that a particular user account is authorized or that every client has been tested.

## Architecture

Android's [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider) is the shared local repository for calendar applications and account sync adapters. Sunlith can display the same Google calendars as another Android calendar app without maintaining a second cloud database.

The [MCP transport specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) defines stdio, in which a client launches a local subprocess, and Streamable HTTP for independent servers. The first Sunlith companion uses stdio. It does not expose a phone or LAN server. Google OAuth uses a temporary loopback listener, which is a separate authorization mechanism, not an MCP HTTP transport.

## Google API behavior

- [Authorization scopes](https://developers.google.com/workspace/calendar/api/auth): `calendar.events` permits viewing and editing events on accessible calendars, subject to calendar permissions; `calendar.calendarlist.readonly` permits choosing calendars and reading their metadata. Neither grants sharing-permission or calendar creation/deletion access. These scopes do not limit event access to the one calendar selected in Sunlith.
- [Create events](https://developers.google.com/workspace/calendar/api/guides/create-events): caller-supplied event IDs help prevent duplicates after an ambiguous failure.
- [Events.insert reference](https://developers.google.com/workspace/calendar/api/v3/reference/events/insert): custom IDs use lowercase base32hex characters, date-time offsets or IANA zones are required, recurring events need their timezone, and all-day ends are exclusive. Invitation email settings are separate from popup reminders.
- [Events.list reference](https://developers.google.com/workspace/calendar/api/v3/reference/events/list): `singleEvents=true` expands recurring series into occurrences and omits series masters. `timeMin` is an exclusive lower bound on event end; `timeMax` is an exclusive upper bound on event start. UTC windows are valid regardless of the event/calendar timezone. Explicit `eventTypes=default` excludes special types that Google otherwise includes; Sunlith permits reading supported special types when requested but does not mutate them. Array parameters are sent as repeated query keys.
- [CalendarList.list](https://developers.google.com/workspace/calendar/api/v3/reference/calendarList/list) and [CalendarList resource](https://developers.google.com/workspace/calendar/api/v3/reference/calendarList): the read-only calendar-list scope is needed to enumerate calendars; omitted `selected` means false.
- [Events.patch reference](https://developers.google.com/workspace/calendar/api/v3/reference/events/patch): omitted fields are retained. Sunlith changes only requested fields and explicitly clears mutually exclusive nested `date`/`dateTime`/`timeZone` fields with null when converting timed/all-day events. The regression test simulates nested merging. Patch costs three quota units; the alpha prioritizes preserving unsupported event fields over replacing the whole resource.
- [Recurring events](https://developers.google.com/workspace/calendar/api/guides/recurringevents): instances and series masters have different identities; “this and following” involves splitting a series. Sunlith's companion does not implement that split.
- [Versioned resources](https://developers.google.com/workspace/calendar/api/guides/version-resources): etags and `If-Match` prevent blind overwrites. Sunlith also checks the currently returned etag before mutation.
- [OAuth token expiration](https://developers.google.com/identity/protocols/oauth2#expiration): external apps in Testing normally issue refresh tokens that expire after seven days when requesting Calendar scopes.
- [OAuth desktop apps](https://developers.google.com/identity/protocols/oauth2/native-app): use desktop client credentials, loopback redirects and the authorization-code flow; Sunlith adds PKCE and state validation through the official Google auth library.

Sunlith validates event date-time offsets against the supplied IANA zone using the runtime timezone database. It rejects DST gaps and accepts either matching offset in a DST fold. Listing windows use generic RFC3339 instants instead. OAuth completion is logged only after token exchange and saving a refresh token; loaded token files must contain a refresh token and valid optional access-token/expiry fields. Cancelled or gone events are idempotent delete results, and cancelled updates are refused.

`get_current_time` defaults to the computer's IANA timezone, or UTC if unavailable. It returns `localIso` with the numeric UTC offset and an English `weekday`, alongside UTC and the zone name. Assistants should use this tool before interpreting relative dates and specify another zone when needed.

The Node companion uses the official `google-auth-library` plus Calendar REST calls. `@modelcontextprotocol/sdk`, `google-auth-library` and `zod` are pinned in its package manifest and lockfile. An attempted `googleapis` dependency added unnecessary transitive packages and audit findings; the final implementation does not depend on it.

## Clients

- [Claude Code MCP](https://code.claude.com/docs/en/mcp) documents local stdio and remote HTTP connections.
- [Codex MCP](https://developers.openai.com/codex/mcp) documents stdio/Streamable HTTP and `config.toml` configuration. This is official OpenAI documentation.
- [Gemini CLI MCP](https://geminicli.com/docs/tools/mcp-server/) documents stdio/SSE/Streamable HTTP and the `mcpServers` settings object.
- [Cursor MCP](https://cursor.com/docs/context/mcp) documents stdio/SSE/Streamable HTTP and `mcp.json`.
- [Grok MCP servers](https://docs.x.ai/build/features/mcp-servers) documents `grok mcp`, stdio `command`/`args`/`env` entries in `~/.grok/config.toml`, `grok mcp doctor`, and compatibility imports from other clients. The [Grok CLI reference](https://docs.x.ai/build/cli/reference) links this setup. This is xAI's official CLI, not an unrelated community project with a similar name.
- [OpenCode MCP](https://opencode.ai/docs/mcp-servers/) and [config](https://opencode.ai/docs/config/) document local servers in `~/.config/opencode/opencode.json`, using `type: local`, a command array and `environment`.
- [Antigravity MCP](https://antigravity.google/docs/mcp/) documents stdio servers in the global `~/.gemini/config/mcp_config.json` or workspace `.agents/mcp_config.json`, CLI `/mcp`, and IDE raw-config settings. It is separate from Gemini CLI's `settings.json`. The Antigravity overview's broad reference to SSE does not establish that every surface supports it; the detailed CLI guide specifies Streamable HTTP for remote servers. Sunlith uses stdio.
- [Muse extending/MCP](https://dev.meta.ai/docs/muse-code/extending) and [configuration](https://dev.meta.ai/docs/muse-code/configuration) document the `mcp_servers` object in `~/.config/muse/settings.json`. Existing settings must include `schema_version: 1`. Local entries use `transport: stdio`, `command`, `args`, and `env`; `mode: optional` avoids aborting the whole agent if this companion cannot start.
- [Pi MCP](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/mcp.md) documents built-in stdio/Streamable HTTP support, user `~/.pi/agent/mcp.json`, trusted-project `.pi/mcp.json`, `pi mcp list` and `/mcp`. Older claims that Pi requires a third-party MCP extension do not apply to current upstream. Its [settings guide](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/settings.md) explains disabling/replacing the built-in MCP extension, so installed versions and extensions still matter.

The companion passed an actual local stdio handshake and tool calls using the official SDK client. The setup guide covers Codex, Claude, Cursor, Grok, OpenCode, Antigravity, Muse Code and Pi, plus Gemini CLI, at the documented configuration level. Live provider sessions with an authenticated Google account remain untested. In particular, do not assume that a separate Antigravity Agent Client Protocol (ACP) runtime inherits the CLI/IDE configuration. Other hosting apps may also select separate user profiles; configure the account and directory used by the running assistant. The Android setup links open host configuration instructions; the APK does not install or configure the host's agents.

## Existing implementation studied

[`nspady/google-calendar-mcp`](https://github.com/nspady/google-calendar-mcp) was active, MIT-licensed, and published as `@cocal/google-calendar-mcp` version `2.8.1` when checked. It supports personal calendar CRUD, recurrence, free/busy and multiple accounts. It is not a Sunlith dependency.

Source review found its [OAuth flow](https://github.com/nspady/google-calendar-mcp/blob/main/src/auth/server.ts) requesting full `calendar` scope, despite documentation mentioning narrower event scopes. Its [HTTP transport](https://github.com/nspady/google-calendar-mcp/blob/main/src/transports/http.ts) contains Origin checks but should not be assumed to provide per-client authorization suitable for public exposure. Its custom event ID support informed Sunlith's retry behavior; Sunlith returns the existing matching creation rather than suggesting a different ID after a duplicate response.

## CalDAV alternative

[Nextcloud with DAVx⁵](https://www.davx5.com/tested-with/nextcloud) can synchronize an open-source calendar backend into Android's provider. That is compatible with the native app's shared-calendar approach, but the current MCP companion is Google-only. A CalDAV adapter needs independent authentication, sync/etag handling and testing before claiming support. Google's [CalDAV interface](https://developers.google.com/workspace/calendar/caldav/v2/guide) also requires OAuth and has protocol-specific limitations; the dedicated Google REST API is simpler for this first adapter.

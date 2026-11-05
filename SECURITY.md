# Security

Do not include real calendar content, OAuth tokens, client secrets, or signing keys in issues or screenshots. Revoke exposed Google credentials immediately through your Google account.

The Android application requests access to the shared calendar provider and optionally notifications/exact alarms. Its computer connection uses network access only while you enable it. Import files are parsed locally with size/event limits; imported guests, attachments, and executable content are not acted on.

The direct connection runs a private HTTPS listener on the phone while its foreground notification is active. It is off by default and does not start at boot. The companion verifies the phone certificate before sending the pairing token. Keep the pairing file private and outside the repository: it grants read and write access to the calendars selected during pairing. Unpair in Sunlith to revoke that file. Pairing credentials are excluded from app backup. Display filters and computer access are separate choices.

Use Wi-Fi or Tailscale. Do not expose the phone port through a public router or reverse proxy. Calendar content is untrusted data and must never be treated as instructions to run commands or change other events.

The MCP companion can read and change events permitted by the authorized Google account. Its OAuth event scope is broad; calendar IDs in prompts are not an account-level permission boundary. The Android app's display filters do not restrict the companion's Google authorization. Run it only with trusted MCP clients under your own OS account. Keep credential/token files in your private profile. Windows token files use the profile's filesystem permissions rather than an encrypted credential vault.

Use GitHub's private vulnerability reporting when available, or contact the repository owner privately. Report steps, affected version, and expected impact without publishing secrets.

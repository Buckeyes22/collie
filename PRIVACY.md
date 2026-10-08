# Privacy

This policy describes the native Android client in this source tree; your Collie server and
configured providers have their own data handling.

## Network access

The app sends API requests to the HTTPS Collie origin you configure.

Those requests include reads, terminal replies, keys, device management and other actions you
select. Paired writes carry the device credential to that server. The client verifies certificates
and does not follow API redirects. It contains no advertising, analytics, Firebase or remote
crash-reporting integration.

Selecting an image uploads it to your server. Using voice input uploads microphone audio to the
server's speech-to-text endpoint; a configured server-side provider may forward it to a cloud
service. Ask your server operator about the provider and retention settings. Nothing here claims
that an agent or server-side provider processes data only on your phone.

## Local data

Connection data and app preferences live on your device.

The pairing credential is encrypted using an Android Keystore-backed AES/GCM key. The app also
holds pane data in memory and persists preferences and drafts through its native state handling.
Android cloud backup and device-transfer backup are disabled. Network access is declared in the
manifest; microphone access is requested at runtime for voice input. Images use the system picker
without broad storage permission.

Screenshots and recent-app previews are not blocked. Consider what is visible before capturing
or sharing a screen.

## Diagnostics

Diagnostic capture is off by default on new installs and can be enabled in Settings.

Explicit settings from an earlier install survive upgrades. Enabling capture records app events,
crashes, pane state, text API requests and responses, including terminal content and sent messages.
Authorization header values and pairing request/response bodies are excluded. This is not a
general-purpose secret scrubber: terminal text or error messages can themselves contain secrets.

The active diagnostic file is plaintext in app-private storage. Rotated files are compressed and
encrypted, with bounded retention.

**Send diagnostics** creates a plaintext ZIP for Android's
share sheet. You choose its destination; the app does not automatically send it to the maintainer.
Review the ZIP and remove sensitive content before sharing. Copies shared to another app follow
that app's storage and privacy behavior.

Disabling capture stops new diagnostic records; it does not erase existing records. The app
clears leftover export files on startup, but recipients may keep copies you shared.

## Disconnect and delete

Disconnect removes the local connection; server-side authorization needs separate revocation.

Revoke the device in server Settings or with `collie devices revoke` on the host. Clearing Android
app storage or uninstalling deletes the app's local preferences, drafts, connection and diagnostics.
It does not delete server uploads, session logs, terminal history, provider data or previously
shared exports. Use the server's administration tools for those records.

## Questions and reports

Report ordinary privacy questions through this fork's issue tracker.

Use [private security reporting](SECURITY.md) for a vulnerability or credential exposure.
Keep credentials and private terminal content out of public issues.

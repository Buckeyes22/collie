# Native push payload contract

This document records the prepared version 1 payload contract shared by the bridge and Android
client. It defines decoding and validation only. It does not enable native push delivery or select a
provider.

## Current implementation

`bridge/native-push-contract.ts` and
`app/src/main/java/com/lateapex/collie/notifications/NativePushPayload.kt` implement pure decoders.
The bridge module also defines generic provider and registration types, but no concrete provider
implements them. Both decoders consume the shared fixture corpus at
`app/src/test/resources/native-push-v1.fixtures.json`. Unknown fields are rejected at the payload
and target levels.

The prepared bridge types are `NativePushProvider<Destination>`, whose `deliver` method returns
`accepted`, `retired`, or `retry` (optionally with `retryAfterMs`), and
`NativePushRegistration<Destination>`, which holds `registrationId`, `pairedDeviceId`,
`installationId`, and a provider-specific `destination`. The destination type is expected to
represent a validated server-private address. These interfaces define a seam; they do not send
messages, authenticate a request, persist registrations, or install a delivery service.

The decoder requires the caller to supply the expected `registrationId`, current time in epoch
milliseconds, and the last accepted sequence for that registration and slot. The caller must treat
the supplied registration ID as trusted local context. The decoder checks the incoming identifier,
expiry, and sequence against those values; it does not authenticate the provider, store the
high-water sequence, or create a notification. Provider authentication at the transport boundary
is indispensable and must happen before decoding. Matching the payload's `registrationId` to a
trusted local ID is a binding check, not authentication.

## Version 1 fields

Every payload has exactly these required fields. Unknown fields are rejected.

| Field | Type and rule |
| --- | --- |
| `schemaVersion` | Integer exactly `1`. |
| `registrationId` | Opaque-format identifier matching `[A-Za-z0-9_-]{1,128}`; must equal the caller-supplied expected ID. |
| `slot` | Per-notification-stream identifier matching `[A-Za-z0-9_-]{1,128}`. |
| `sequence` | Positive safe integer, strictly greater than the caller-supplied last sequence for this registration and slot. |
| `expiresAt` | Future safe integer Unix time in milliseconds; it must be greater than `nowMs` and no more than 24 hours after it. |
| `kind` | Exactly `blocked`, `done`, `update`, or `clear`. |

The entire UTF-8 JSON payload is limited to 4096 bytes. Kotlin also rejects strings longer than
4096 UTF-16 code units before allocating the UTF-8 byte copy, then enforces the byte limit. Text
field limits use the string length reported by JavaScript and Kotlin (UTF-16 code units). Numeric
values must be integral safe integers no greater than `9007199254740991`. The receiver's `nowMs`
and `lastSequence` inputs must also be nonnegative safe integers.

For `blocked`, `done`, and `update`, the payload also requires `title`, `body`, and `target`.
`title` is nonblank text of at most 160 characters; `body` is nonblank text of at most 512
characters. Neither may contain C0 control characters or DEL. `renotify` is an optional Boolean;
when absent, both decoders normalize it to `false`.

For `clear`, `title`, `body`, `renotify`, and `target` must all be absent. The decoder only returns
the validated clear; it does not persist it. Future integration must persist the monotonic sequence
high-water mark server-side for each `(registrationId, slot)` across restarts. The receiver must
also persist slot state and keep a clear tombstone until that registration is retired, so a delayed
older notification cannot resurrect a cleared slot after process restart.

## Targets

Targets are internal Collie navigation identities. They have no URL or executable command field.
Unknown target fields are rejected.

| Notification kind | Allowed target |
| --- | --- |
| `blocked`, `done` | `{"screen":"home"}` with optional `host` and `session`, or `{"screen":"pane","paneId":"…"}` with optional `host` and `session`. |
| `update` | Exactly `{"screen":"updates"}`. |
| `clear` | No target. |

`paneId` is required for a `pane` target and forbidden for `home`. `host`, `session`, and `paneId`
must be nonblank text of at most 256 characters and contain no C0 control characters or DEL.
Scope values identify a Collie host/session; they are not origins. An update target accepts no scope
or additional fields.

The payload has no field for an origin, provider credential, bearer token, pairing code, or command.
Targets encode Collie screen and scope identity, not an external URL; an external destination
cannot be supplied through a target.

## Proposed registration and delivery boundary

These are integration constraints for future provider work; they are not implemented endpoints or
delivery behavior, even though the pure TypeScript interfaces for the seam now exist:

- Registration must be authorized as the paired device that owns it. The transport boundary must
  authenticate delivery before the decoder runs; a matching `registrationId` alone is not proof.
- The server should mint an opaque registration generation ID for the installation. That ID is a
  routing/version identifier, not a secret or credential. Store it with that paired device's
  registration state.
- Replacing an installation's registration should replace only that installation on the paired
  device. It must not replace registrations belonging to another paired device.
- Revoking the paired device, unpairing it, or disconnecting it must suppress future delivery and
  clear its registration state.
- Native provider records and delivery must be additive to the existing browser Web Push flow; keep
  existing Web Push subscriptions and behavior intact.
- Persist and compare each `(registrationId, slot)` sequence high-water mark. A `clear` advances the
  server-side monotonic sequence across restarts. The receiver retains its slot tombstone until
  that registration is retired. Expired, duplicate, and out-of-order payloads must not be acted on.
- A notification tap may navigate to the decoded Collie target only. It never grants device-write
  authority or triggers a terminal command.

Provider choice, provider credentials, registration routes, delivery routes, Android notification
permission, notification UI/channels, durable sequence ledgers and receiver tombstones, delivery,
and end-to-end delivery tests remain unimplemented. Provider choices are deferred, not
recommendations. Android's payload `toString()` omits title and body so incidental logging does not
expose display text.

## Contract checks

The bridge and Android tests consume the same 48-case fixture file. It covers accepted targets,
expiry, duplicates, reordering, field limits, Unicode whitespace and prohibited fields. Separate
tests reject malformed JSON, oversized input and invalid receiver context.

Validation passed 49 Bun contract tests and 3 Android JUnit tests, including the shared corpus.
The Kotlin tests used the cached Kotlin 2.0.21 compiler and JUnit directly because Gradle could
not start its local daemon socket in this session. These results establish decoder behavior;
device and live-delivery acceptance remain open.
